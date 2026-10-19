package com.morpheus.debugbridge.api

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.morpheus.debugbridge.TestHelpers
import com.morpheus.debugbridge.model.DebugState
import com.morpheus.debugbridge.model.DebugStatus
import com.morpheus.debugbridge.model.ErrorResponse
import com.morpheus.debugbridge.model.ProjectsResponse
import com.morpheus.debugbridge.model.ProjectInfo
import com.morpheus.debugbridge.model.SessionActionResponse
import com.morpheus.debugbridge.model.SessionDetailResponse
import com.morpheus.debugbridge.model.SessionsResponse
import com.morpheus.debugbridge.service.AppRegistryService
import com.morpheus.debugbridge.service.DebugStateService
import com.morpheus.debugbridge.util.RateLimiter
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for the V2 API routes (V2Routes).
 *
 * The real route configuration is mounted in Ktor's test application and the
 * IntelliJ service singletons are replaced with mocks:
 * - AppRegistryService.getInstance() -> mock registry
 * - DebugStateService.getInstance() -> mock state service
 * - RateLimiter -> allows all requests unless a test overrides it
 * - ApplicationManager.getApplication().invokeLater -> runs the block immediately
 *   (tests that exercise EDT-guarded actions use mockApplicationEdt)
 */
@DisplayName("V2Routes")
class V2RoutesTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private lateinit var registry: AppRegistryService
    private lateinit var stateService: DebugStateService

    @BeforeEach
    fun setUp() {
        registry = mockk(relaxed = true)
        stateService = mockk(relaxed = true)

        mockkObject(AppRegistryService.Companion)
        every { AppRegistryService.getInstance() } returns registry

        mockkObject(DebugStateService.Companion)
        every { DebugStateService.getInstance() } returns stateService

        mockkObject(RateLimiter)
        every { RateLimiter.isAllowed(any()) } returns true

        // Default: no session state (avoids relaxed mocks leaking into serialization);
        // tests that need a state override these stubs.
        every { stateService.getStateForSession(any()) } returns null
        every { stateService.getStateWithStackTraceForSession(any()) } returns null
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /** Mount the real V2 routes with JSON content negotiation. */
    private fun Application.configureV2TestApp() {
        install(ContentNegotiation) {
            json(json)
        }
        routing {
            V2Routes.apply { configureV2Routes() }
        }
    }

    /**
     * Route actions are guarded by runOnEdt. Without an IntelliJ application
     * there is no EDT, so invokeLater executes the runnable immediately.
     */
    private fun mockApplicationEdt() {
        val app = mockk<com.intellij.openapi.application.Application>()
        every { app.invokeLater(any<Runnable>()) } answers { firstArg<Runnable>().run() }
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
    }

    private fun createProjectInfo(
        id: String = "p1",
        name: String = "demo",
        basePath: String = "/home/user/demo"
    ): AppRegistryService.ProjectInfo {
        val project = mockk<Project>(relaxed = true)
        every { project.name } returns name
        return AppRegistryService.ProjectInfo(
            projectId = id,
            project = project,
            name = name,
            basePath = basePath
        )
    }

    private fun createSessionRef(
        sessionId: String,
        projectId: String,
        session: XDebugSession
    ): AppRegistryService.SessionReference {
        return AppRegistryService.SessionReference(
            sessionId = sessionId,
            projectId = projectId,
            session = session,
            sessionName = session.sessionName
        )
    }

    @Nested
    @DisplayName("Discovery Endpoints")
    inner class DiscoveryEndpointsTest {

        @Test
        fun `GET projects should return all registered projects with session counts`() = testApplication {
            application { configureV2TestApp() }

            val projectInfo = createProjectInfo(id = "p1", name = "demo", basePath = "/home/user/demo")
            every { registry.getAllProjects() } returns listOf(projectInfo)
            every { registry.getSessionsForProject("p1") } returns listOf(
                createSessionRef("s1", "p1", TestHelpers.createMockSession("Run 1")),
                createSessionRef("s2", "p1", TestHelpers.createMockSession("Run 2"))
            )

            val response = client.get("/projects")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = json.decodeFromString<ProjectsResponse>(response.bodyAsText())
            assertThat(body.projects).hasSize(1)
            assertThat(body.projects[0].id).isEqualTo("p1")
            assertThat(body.projects[0].name).isEqualTo("demo")
            assertThat(body.projects[0].path).isEqualTo("/home/user/demo")
            assertThat(body.projects[0].sessionCount).isEqualTo(2)
            assertThat(body.count).isEqualTo(1)
        }

        @Test
        fun `GET projects should return empty list when no projects are open`() = testApplication {
            application { configureV2TestApp() }

            val response = client.get("/projects")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = json.decodeFromString<ProjectsResponse>(response.bodyAsText())
            assertThat(body.projects).isEmpty()
            assertThat(body.count).isZero()
        }

        @Test
        fun `GET sessions should map session lifecycle to status`() = testApplication {
            application { configureV2TestApp() }

            val stoppedSession = TestHelpers.createMockSession("StoppedApp")
            every { stoppedSession.isStopped } returns true

            val suspendedSession = TestHelpers.createMockSession(
                name = "SuspendedApp",
                isSuspended = true,
                currentFrame = TestHelpers.createMockStackFrame("App.java", "/src/App.java", 42)
            )

            val runningSession = TestHelpers.createMockSession(
                name = "RunningApp",
                isSuspended = false
            )

            val projectInfo = createProjectInfo(id = "p1", name = "demo")
            every { registry.getProject("p1") } returns projectInfo
            every { registry.getAllSessions() } returns listOf(
                createSessionRef("s-stopped", "p1", stoppedSession),
                createSessionRef("s-suspended", "p1", suspendedSession),
                createSessionRef("s-running", "p1", runningSession)
            )

            val response = client.get("/sessions")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = json.decodeFromString<SessionsResponse>(response.bodyAsText())
            assertThat(body.sessions).hasSize(3)

            val stopped = body.sessions.first { it.id == "s-stopped" }
            assertThat(stopped.status).isEqualTo(DebugStatus.TERMINATED)

            val suspended = body.sessions.first { it.id == "s-suspended" }
            assertThat(suspended.status).isEqualTo(DebugStatus.SUSPENDED)
            assertThat(suspended.projectName).isEqualTo("demo")
            assertThat(suspended.location).isNotNull
            assertThat(suspended.location?.file).isEqualTo("App.java")
            assertThat(suspended.location?.line).isEqualTo(42)

            val running = body.sessions.first { it.id == "s-running" }
            assertThat(running.status).isEqualTo(DebugStatus.RUNNING)
            assertThat(running.location).isNull()
        }
    }

    @Nested
    @DisplayName("Project Endpoints")
    inner class ProjectEndpointsTest {

        @Test
        fun `GET project by id should return project details`() = testApplication {
            application { configureV2TestApp() }

            val projectInfo = createProjectInfo(id = "p1", name = "demo", basePath = "/home/user/demo")
            every { registry.getProject("p1") } returns projectInfo
            every { registry.getSessionsForProject("p1") } returns listOf(
                createSessionRef("s1", "p1", TestHelpers.createMockSession())
            )

            val response = client.get("/projects/p1")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = json.decodeFromString<ProjectInfo>(response.bodyAsText())
            assertThat(body.id).isEqualTo("p1")
            assertThat(body.name).isEqualTo("demo")
            assertThat(body.path).isEqualTo("/home/user/demo")
            assertThat(body.sessionCount).isEqualTo(1)
        }

        @Test
        fun `GET project by id should return 404 for unknown project`() = testApplication {
            application { configureV2TestApp() }

            every { registry.getProject("unknown") } returns null
            every { registry.getAllProjects() } returns listOf(createProjectInfo(id = "p1"))

            val response = client.get("/projects/unknown")
            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)

            val body = json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertThat(body.error).isEqualTo("PROJECT_NOT_FOUND")
            assertThat(body.availableProjects).containsExactly("p1")
        }

        @Test
        fun `GET project sessions should list sessions of the project`() = testApplication {
            application { configureV2TestApp() }

            val projectInfo = createProjectInfo(id = "p1", name = "demo")
            every { registry.getProject("p1") } returns projectInfo
            every { registry.getSessionsForProject("p1") } returns listOf(
                createSessionRef("s1", "p1", TestHelpers.createMockSession(name = "Run demo", isSuspended = false))
            )

            val response = client.get("/projects/p1/sessions")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = json.decodeFromString<SessionsResponse>(response.bodyAsText())
            assertThat(body.sessions).hasSize(1)
            assertThat(body.sessions[0].id).isEqualTo("s1")
            assertThat(body.sessions[0].name).isEqualTo("Run demo")
            assertThat(body.sessions[0].projectName).isEqualTo("demo")
            assertThat(body.sessions[0].status).isEqualTo(DebugStatus.RUNNING)
        }

        @Test
        fun `GET project sessions should return 404 for unknown project`() = testApplication {
            application { configureV2TestApp() }

            every { registry.getProject("unknown") } returns null

            val response = client.get("/projects/unknown/sessions")
            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)

            val body = json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertThat(body.error).isEqualTo("PROJECT_NOT_FOUND")
        }

        @Test
        fun `GET project configurations should return 404 for unknown project`() = testApplication {
            application { configureV2TestApp() }

            every { registry.getProject("unknown") } returns null

            val response = client.get("/projects/unknown/configurations")
            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)

            val body = json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertThat(body.error).isEqualTo("PROJECT_NOT_FOUND")
        }
    }

    @Nested
    @DisplayName("Session Detail Endpoints")
    inner class SessionDetailEndpointsTest {

        @Test
        fun `GET session by id should return session detail with state`() = testApplication {
            application { configureV2TestApp() }

            val session = TestHelpers.createMockSession(name = "Run demo", isSuspended = true)
            val projectInfo = createProjectInfo(id = "p1", name = "demo")
            val state = DebugState(
                status = DebugStatus.SUSPENDED,
                sessionName = "Run demo",
                location = null,
                stackTrace = emptyList()
            )

            every { registry.getSession("s1") } returns createSessionRef("s1", "p1", session)
            every { registry.getProject("p1") } returns projectInfo
            every { stateService.getStateWithStackTraceForSession("s1") } returns state

            val response = client.get("/sessions/s1")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = json.decodeFromString<SessionDetailResponse>(response.bodyAsText())
            assertThat(body.id).isEqualTo("s1")
            assertThat(body.name).isEqualTo("Run demo")
            assertThat(body.projectId).isEqualTo("p1")
            assertThat(body.projectName).isEqualTo("demo")
            assertThat(body.status).isEqualTo(DebugStatus.SUSPENDED)
        }

        @Test
        fun `GET session by id should return 404 for unknown session`() = testApplication {
            application { configureV2TestApp() }

            every { registry.getSession("unknown") } returns null
            every { registry.getAllSessions() } returns listOf(
                createSessionRef("s1", "p1", TestHelpers.createMockSession())
            )

            val response = client.get("/sessions/unknown")
            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)

            val body = json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertThat(body.error).isEqualTo("SESSION_NOT_FOUND")
            assertThat(body.availableSessions).containsExactly("s1")
        }

        @Test
        fun `GET session by id should return 500 when state extraction fails`() = testApplication {
            application { configureV2TestApp() }

            val session = TestHelpers.createMockSession(name = "Run demo")
            every { registry.getSession("s1") } returns createSessionRef("s1", "p1", session)
            every { stateService.getStateWithStackTraceForSession("s1") } returns null

            val response = client.get("/sessions/s1")
            assertThat(response.status).isEqualTo(HttpStatusCode.InternalServerError)

            val body = json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertThat(body.error).isEqualTo("STATE_EXTRACTION_FAILED")
        }

        @Test
        fun `GET session variables should return 404 for unknown session`() = testApplication {
            application { configureV2TestApp() }

            every { registry.getDebugSession("unknown") } returns null

            val response = client.get("/sessions/unknown/variables")
            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)

            val body = json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertThat(body.error).isEqualTo("SESSION_NOT_FOUND")
        }
    }

    @Nested
    @DisplayName("Session Action Endpoints")
    inner class SessionActionEndpointsTest {

        @BeforeEach
        fun setUpEdt() {
            mockApplicationEdt()
        }

        @Test
        fun `POST step-over should execute step on suspended session`() = testApplication {
            application { configureV2TestApp() }

            val session = TestHelpers.createMockSession(name = "Run demo", isSuspended = true)
            val projectInfo = createProjectInfo(id = "p1", name = "demo")
            val state = DebugState(DebugStatus.SUSPENDED, "Run demo", null, emptyList())

            every { registry.getSession("s1") } returns createSessionRef("s1", "p1", session)
            every { registry.getProject("p1") } returns projectInfo
            every { stateService.getStateForSession("s1") } returns state

            val response = client.post("/sessions/s1/step-over")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = json.decodeFromString<SessionActionResponse>(response.bodyAsText())
            assertThat(body.success).isTrue()
            assertThat(body.message).isEqualTo("step-over executed")
            assertThat(body.sessionId).isEqualTo("s1")
            assertThat(body.session).isNotNull
            assertThat(body.session?.status).isEqualTo(DebugStatus.SUSPENDED)

            verify { session.stepOver(false) }
            verify { registry.touchSession("s1") }
        }

        @Test
        fun `POST step-into should execute step into on suspended session`() = testApplication {
            application { configureV2TestApp() }

            val session = TestHelpers.createMockSession(name = "Run demo", isSuspended = true)
            every { registry.getSession("s1") } returns createSessionRef("s1", "p1", session)
            every { stateService.getStateForSession("s1") } returns null

            val response = client.post("/sessions/s1/step-into")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            verify { session.stepInto() }
        }

        @Test
        fun `POST step-out should execute step out on suspended session`() = testApplication {
            application { configureV2TestApp() }

            val session = TestHelpers.createMockSession(name = "Run demo", isSuspended = true)
            every { registry.getSession("s1") } returns createSessionRef("s1", "p1", session)
            every { stateService.getStateForSession("s1") } returns null

            val response = client.post("/sessions/s1/step-out")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            verify { session.stepOut() }
        }

        @Test
        fun `POST step-over should return 409 when session is not suspended`() = testApplication {
            application { configureV2TestApp() }

            val session = TestHelpers.createMockSession(name = "Run demo", isSuspended = false)
            every { registry.getSession("s1") } returns createSessionRef("s1", "p1", session)

            val response = client.post("/sessions/s1/step-over")
            assertThat(response.status).isEqualTo(HttpStatusCode.Conflict)

            val body = json.decodeFromString<SessionActionResponse>(response.bodyAsText())
            assertThat(body.success).isFalse()
            assertThat(body.message).contains("suspended")
        }

        @Test
        fun `POST step-over should return 410 when session is stopped`() = testApplication {
            application { configureV2TestApp() }

            val session = TestHelpers.createMockSession(name = "Run demo", isSuspended = true)
            every { session.isStopped } returns true
            every { registry.getSession("s1") } returns createSessionRef("s1", "p1", session)

            val response = client.post("/sessions/s1/step-over")
            assertThat(response.status).isEqualTo(HttpStatusCode.Gone)

            val body = json.decodeFromString<SessionActionResponse>(response.bodyAsText())
            assertThat(body.success).isFalse()
            assertThat(body.message).contains("stopped")
        }

        @Test
        fun `POST step-over should return 404 for unknown session`() = testApplication {
            application { configureV2TestApp() }

            every { registry.getSession("unknown") } returns null
            every { registry.getAllSessions() } returns emptyList()

            val response = client.post("/sessions/unknown/step-over")
            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)

            val body = json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertThat(body.error).isEqualTo("SESSION_NOT_FOUND")
        }

        @Test
        fun `POST resume should not require suspended session`() = testApplication {
            application { configureV2TestApp() }

            val session = TestHelpers.createMockSession(name = "Run demo", isSuspended = false)
            every { registry.getSession("s1") } returns createSessionRef("s1", "p1", session)
            every { stateService.getStateForSession("s1") } returns null

            val response = client.post("/sessions/s1/resume")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = json.decodeFromString<SessionActionResponse>(response.bodyAsText())
            assertThat(body.success).isTrue()
            assertThat(body.message).isEqualTo("resume executed")
            verify { session.resume() }
        }
    }

    @Nested
    @DisplayName("Delete Session Endpoint")
    inner class DeleteSessionEndpointTest {

        @Test
        fun `DELETE session should stop the session`() = testApplication {
            application { configureV2TestApp() }
            mockApplicationEdt()

            val session = TestHelpers.createMockSession(name = "Run demo")
            every { registry.getDebugSession("s1") } returns session

            val response = client.delete("/sessions/s1")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = json.decodeFromString<SessionActionResponse>(response.bodyAsText())
            assertThat(body.success).isTrue()
            assertThat(body.message).isEqualTo("Debug session stopped")
            assertThat(body.sessionId).isEqualTo("s1")

            verify { session.stop() }
        }

        @Test
        fun `DELETE session should return 404 for unknown session`() = testApplication {
            application { configureV2TestApp() }

            every { registry.getDebugSession("unknown") } returns null

            val response = client.delete("/sessions/unknown")
            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)

            val body = json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertThat(body.error).isEqualTo("SESSION_NOT_FOUND")
        }
    }

    @Nested
    @DisplayName("Evaluate Endpoint")
    inner class EvaluateEndpointTest {

        @Test
        fun `POST evaluate should return 404 for unknown session`() = testApplication {
            application { configureV2TestApp() }

            every { registry.getDebugSession("unknown") } returns null

            val response = client.post("/sessions/unknown/evaluate") {
                contentType(ContentType.Application.Json)
                setBody("""{"expression": "1 + 1"}""")
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)

            val body = json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertThat(body.error).isEqualTo("SESSION_NOT_FOUND")
        }

        @Test
        fun `POST evaluate should return 409 when session is not suspended`() = testApplication {
            application { configureV2TestApp() }

            val session = TestHelpers.createMockSession(name = "Run demo", isSuspended = false)
            every { registry.getDebugSession("s1") } returns session

            val response = client.post("/sessions/s1/evaluate") {
                contentType(ContentType.Application.Json)
                setBody("""{"expression": "1 + 1"}""")
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.Conflict)

            val body = json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertThat(body.error).isEqualTo("SESSION_NOT_SUSPENDED")
        }
    }

    @Nested
    @DisplayName("Rate Limiting")
    inner class RateLimitingTest {

        @Test
        fun `should return 429 when rate limit is exceeded`() = testApplication {
            application { configureV2TestApp() }

            every { RateLimiter.isAllowed("read") } returns false

            val response = client.get("/projects")
            assertThat(response.status).isEqualTo(HttpStatusCode.TooManyRequests)

            val body = json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertThat(body.error).isEqualTo("RATE_LIMIT_EXCEEDED")
        }
    }
}
