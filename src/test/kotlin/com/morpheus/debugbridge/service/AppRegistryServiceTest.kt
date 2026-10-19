package com.morpheus.debugbridge.service

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Unit tests for AppRegistryService.
 *
 * AppRegistryService is an IntelliJ application-level service and the single
 * source of truth for projects and debug sessions. For unit testing, instances
 * are created directly (bypassing the service singleton) and IntelliJ
 * interfaces (Project, XDebugSession) are mocked with MockK.
 */
@DisplayName("AppRegistryService")
class AppRegistryServiceTest {

    private lateinit var registry: AppRegistryService

    @BeforeEach
    fun setUp() {
        // Create a fresh instance for each test (bypassing singleton)
        registry = AppRegistryService()
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // Helper to create a mock project with a stable ID
    private fun createMockProject(
        name: String = "demo",
        basePath: String? = "/home/user/demo",
        locationHash: String = "hash-$name",
        isDefault: Boolean = false
    ): Project {
        val project = mockk<Project>(relaxed = true)
        every { project.name } returns name
        every { project.basePath } returns basePath
        every { project.locationHash } returns locationHash
        every { project.isDefault } returns isDefault
        return project
    }

    // Helper to create a mock debug session
    private fun createMockSession(name: String = "Run demo"): XDebugSession {
        val session = mockk<XDebugSession>(relaxed = true)
        every { session.sessionName } returns name
        return session
    }

    @Nested
    @DisplayName("Project ID Generation")
    inner class ProjectIdGenerationTest {

        @Test
        fun `generateProjectId should prefer locationHash when available`() {
            val project = createMockProject(locationHash = "stable-hash-123")

            assertThat(AppRegistryService.generateProjectId(project)).isEqualTo("stable-hash-123")
        }

        @Test
        fun `generateProjectId should fall back to SHA-256 of basePath`() {
            val project = createMockProject(basePath = "/home/user/demo", locationHash = "")

            val id = AppRegistryService.generateProjectId(project)

            assertThat(id).hasSize(64)
            assertThat(id).matches("[0-9a-f]{64}")
        }

        @Test
        fun `generateProjectId should throw when no locationHash and no basePath`() {
            val project = createMockProject(basePath = null, locationHash = "")

            assertThatThrownBy { AppRegistryService.generateProjectId(project) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Cannot generate project ID")
        }

        @Test
        fun `generateIdFromPath should be deterministic`() {
            val id1 = AppRegistryService.generateIdFromPath("/home/user/demo")
            val id2 = AppRegistryService.generateIdFromPath("/home/user/demo")

            assertThat(id1).isEqualTo(id2)
        }

        @Test
        fun `generateIdFromPath should produce different IDs for different paths`() {
            val id1 = AppRegistryService.generateIdFromPath("/home/user/project-a")
            val id2 = AppRegistryService.generateIdFromPath("/home/user/project-b")

            assertThat(id1).isNotEqualTo(id2)
        }

        @Test
        fun `generateIdFromPath should return full 64 character hex hash`() {
            val id = AppRegistryService.generateIdFromPath("/opt/projects/shop")

            assertThat(id).hasSize(64)
            assertThat(id).matches("[0-9a-f]{64}")
        }
    }

    @Nested
    @DisplayName("Project Registration")
    inner class ProjectRegistrationTest {

        @Test
        fun `registerProject should assign stable ID and store project info`() {
            val project = createMockProject(name = "shop", basePath = "/home/user/shop")

            val projectId = registry.registerProject(project)

            assertThat(projectId).isEqualTo("hash-shop")
            val info = registry.getProject(projectId)
            assertThat(info).isNotNull
            assertThat(info?.name).isEqualTo("shop")
            assertThat(info?.basePath).isEqualTo("/home/user/shop")
            assertThat(info?.project).isSameAs(project)
        }

        @Test
        fun `registerProject should reject the default project`() {
            val project = createMockProject(isDefault = true)

            assertThatThrownBy { registry.registerProject(project) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("default project")
        }

        @Test
        fun `registerProject should reject project without basePath`() {
            // locationHash present so ID generation succeeds, but basePath is null
            val project = createMockProject(basePath = null, locationHash = "hash-x")

            assertThatThrownBy { registry.registerProject(project) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("without a basePath")
        }

        @Test
        fun `registerProject should throw IllegalStateException when neither hash nor path exists`() {
            val project = createMockProject(basePath = null, locationHash = "")

            assertThatThrownBy { registry.registerProject(project) }
                .isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        fun `registerProjectOrNull should return ID for valid project`() {
            val project = createMockProject(name = "app")

            assertThat(registry.registerProjectOrNull(project)).isEqualTo("hash-app")
        }

        @Test
        fun `registerProjectOrNull should return null for default project`() {
            val project = createMockProject(isDefault = true)

            assertThat(registry.registerProjectOrNull(project)).isNull()
        }

        @Test
        fun `registerProjectOrNull should return null for project without basePath`() {
            val project = createMockProject(basePath = null, locationHash = "hash-x")

            assertThat(registry.registerProjectOrNull(project)).isNull()
        }

        @Test
        fun `isProjectRegistered should reflect registration state`() {
            val project = createMockProject(name = "app")

            assertThat(registry.isProjectRegistered(project)).isFalse()

            registry.registerProject(project)

            assertThat(registry.isProjectRegistered(project)).isTrue()
        }

        @Test
        fun `getProjectCount should track registered projects`() {
            assertThat(registry.getProjectCount()).isZero()

            registry.registerProject(createMockProject(name = "a"))
            registry.registerProject(createMockProject(name = "b"))

            assertThat(registry.getProjectCount()).isEqualTo(2)
        }

        @Test
        fun `getAllProjects should return snapshot of registered projects`() {
            registry.registerProject(createMockProject(name = "a"))
            registry.registerProject(createMockProject(name = "b"))

            val all = registry.getAllProjects()

            assertThat(all.map { it.name }).containsExactlyInAnyOrder("a", "b")
        }

        @Test
        fun `getProjectByObject should find project by reference`() {
            val project = createMockProject(name = "a")
            registry.registerProject(project)

            assertThat(registry.getProjectByObject(project)?.projectId).isEqualTo("hash-a")
        }
    }

    @Nested
    @DisplayName("Project Unregistration")
    inner class ProjectUnregistrationTest {

        @Test
        fun `unregisterProjectById should remove project`() {
            val project = createMockProject(name = "a")
            registry.registerProject(project)

            registry.unregisterProjectById("hash-a")

            assertThat(registry.getProjectCount()).isZero()
            assertThat(registry.getProject("hash-a")).isNull()
        }

        @Test
        fun `unregisterProjectById should remove all sessions of the project`() {
            val project = createMockProject(name = "a")
            registry.registerProject(project)
            registry.registerSession("s1", "hash-a", createMockSession("Run 1"))
            registry.registerSession("s2", "hash-a", createMockSession("Run 2"))

            registry.unregisterProjectById("hash-a")

            assertThat(registry.getAllSessions()).isEmpty()
            assertThat(registry.hasActiveSessions()).isFalse()
        }

        @Test
        fun `unregisterProjectById should clear lastTouched session`() {
            val project = createMockProject(name = "a")
            registry.registerProject(project)
            registry.registerSession("s1", "hash-a", createMockSession())

            registry.unregisterProjectById("hash-a")

            assertThat(registry.getLastTouchedSession()).isNull()
        }

        @Test
        fun `unregisterProjectById should keep other projects and their sessions`() {
            val projectA = createMockProject(name = "a")
            val projectB = createMockProject(name = "b")
            registry.registerProject(projectA)
            registry.registerProject(projectB)
            registry.registerSession("s1", "hash-a", createMockSession())
            registry.registerSession("s2", "hash-b", createMockSession())

            registry.unregisterProjectById("hash-a")

            assertThat(registry.getProjectCount()).isEqualTo(1)
            assertThat(registry.getSessionsForProject("hash-b")).hasSize(1)
        }

        @Test
        fun `unregisterProjectById should be a no-op for unknown project`() {
            registry.registerProject(createMockProject(name = "a"))

            registry.unregisterProjectById("does-not-exist")

            assertThat(registry.getProjectCount()).isEqualTo(1)
        }

        @Test
        fun `unregisterProject should remove project by object`() {
            val project = createMockProject(name = "a")
            registry.registerProject(project)

            registry.unregisterProject(project)

            assertThat(registry.getProjectCount()).isZero()
        }
    }

    @Nested
    @DisplayName("Session Registration")
    inner class SessionRegistrationTest {

        @Test
        fun `registerSession should store session reference`() {
            val session = createMockSession("Run app")
            registry.registerProject(createMockProject(name = "a"))

            val ref = registry.registerSession("s1", "hash-a", session)

            assertThat(ref.sessionId).isEqualTo("s1")
            assertThat(ref.projectId).isEqualTo("hash-a")
            assertThat(ref.session).isSameAs(session)
            assertThat(ref.sessionName).isEqualTo("Run app")
            assertThat(registry.getSession("s1")).isSameAs(ref)
        }

        @Test
        fun `registerSession should index session by project`() {
            registry.registerProject(createMockProject(name = "a"))
            registry.registerSession("s1", "hash-a", createMockSession())
            registry.registerSession("s2", "hash-a", createMockSession())

            val sessions = registry.getSessionsForProject("hash-a")

            assertThat(sessions.map { it.sessionId }).containsExactlyInAnyOrder("s1", "s2")
        }

        @Test
        fun `registerSession should make session the last touched`() {
            registry.registerProject(createMockProject(name = "a"))
            registry.registerSession("s1", "hash-a", createMockSession("First"))
            registry.registerSession("s2", "hash-a", createMockSession("Second"))

            assertThat(registry.getLastTouchedSession()?.sessionId).isEqualTo("s2")
        }

        @Test
        fun `unregisterSession should remove session from all indexes`() {
            val session = createMockSession()
            registry.registerProject(createMockProject(name = "a"))
            registry.registerSession("s1", "hash-a", session)

            registry.unregisterSession("s1")

            assertThat(registry.getSession("s1")).isNull()
            assertThat(registry.getDebugSession("s1")).isNull()
            assertThat(registry.findSessionId(session)).isNull()
            assertThat(registry.getSessionsForProject("hash-a")).isEmpty()
            assertThat(registry.getLastTouchedSession()).isNull()
            assertThat(registry.hasActiveSessions()).isFalse()
        }

        @Test
        fun `unregisterSession should clear lastTouched when the touched session is removed`() {
            registry.registerProject(createMockProject(name = "a"))
            registry.registerSession("s1", "hash-a", createMockSession())
            registry.registerSession("s2", "hash-a", createMockSession())

            registry.unregisterSession("s2")

            assertThat(registry.getLastTouchedSession()).isNull()
            assertThat(registry.getSessionsForProject("hash-a").map { it.sessionId })
                .containsExactly("s1")
        }

        @Test
        fun `findSessionId should resolve session ID from debug session object`() {
            val session = createMockSession()
            registry.registerProject(createMockProject(name = "a"))
            registry.registerSession("s1", "hash-a", session)

            assertThat(registry.findSessionId(session)).isEqualTo("s1")
            assertThat(registry.getDebugSession("s1")).isSameAs(session)
        }

        @Test
        fun `getSessionCount should track active sessions`() {
            registry.registerProject(createMockProject(name = "a"))
            assertThat(registry.getSessionCount()).isZero()

            registry.registerSession("s1", "hash-a", createMockSession())
            registry.registerSession("s2", "hash-a", createMockSession())

            assertThat(registry.getSessionCount()).isEqualTo(2)
        }
    }

    @Nested
    @DisplayName("Touch Session")
    inner class TouchSessionTest {

        @Test
        fun `touchSession should mark existing session as last touched`() {
            registry.registerProject(createMockProject(name = "a"))
            registry.registerSession("s1", "hash-a", createMockSession())
            registry.registerSession("s2", "hash-a", createMockSession())

            registry.touchSession("s1")

            assertThat(registry.getLastTouchedSession()?.sessionId).isEqualTo("s1")
        }

        @Test
        fun `touchSession should be a no-op for unknown session`() {
            registry.registerProject(createMockProject(name = "a"))
            registry.registerSession("s1", "hash-a", createMockSession())

            registry.touchSession("unknown")

            assertThat(registry.getLastTouchedSession()?.sessionId).isEqualTo("s1")
        }

        @Test
        fun `touchSession on empty registry should not throw`() {
            registry.touchSession("unknown")

            assertThat(registry.getLastTouchedSession()).isNull()
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCasesTest {

        @Test
        fun `lookups on empty registry should return null or false`() {
            assertThat(registry.getProject("p1")).isNull()
            assertThat(registry.getSession("s1")).isNull()
            assertThat(registry.getDebugSession("s1")).isNull()
            assertThat(registry.getSessionsForProject("p1")).isEmpty()
            assertThat(registry.getAllProjects()).isEmpty()
            assertThat(registry.getAllSessions()).isEmpty()
            assertThat(registry.getLastTouchedSession()).isNull()
            assertThat(registry.hasActiveSessions()).isFalse()
            assertThat(registry.getProjectCount()).isZero()
            assertThat(registry.getSessionCount()).isZero()
        }

        @Test
        fun `isProjectRegistered should return false when ID generation fails`() {
            val project = createMockProject(basePath = null, locationHash = "")

            assertThat(registry.isProjectRegistered(project)).isFalse()
        }

        @Test
        fun `getProjectByObject should return null when ID generation fails`() {
            val project = createMockProject(basePath = null, locationHash = "")

            assertThat(registry.getProjectByObject(project)).isNull()
        }

        @Test
        fun `should handle rapid register and unregister cycles`() {
            val project = createMockProject(name = "a")
            registry.registerProject(project)

            repeat(10) { i ->
                registry.registerSession("s$i", "hash-a", createMockSession())
                registry.unregisterSession("s$i")
            }

            assertThat(registry.hasActiveSessions()).isFalse()
            assertThat(registry.getProjectCount()).isEqualTo(1)
        }

        @Test
        fun `should remain consistent under concurrent access`() {
            val project = createMockProject(name = "a")
            registry.registerProject(project)

            val threads = 8
            val iterations = 100
            val pool = Executors.newFixedThreadPool(threads)
            val latch = CountDownLatch(threads)

            try {
                val futures = (0 until threads).map { t ->
                    pool.submit {
                        try {
                            repeat(iterations) { i ->
                                val sessionId = "t$t-s$i"
                                registry.registerSession(sessionId, "hash-a", createMockSession())
                                registry.touchSession(sessionId)
                                registry.unregisterSession(sessionId)
                            }
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue()
                // get() rethrows worker exceptions; discarded futures would hide them
                futures.forEach { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

            // All sessions were unregistered; registry must be consistent
            assertThat(registry.hasActiveSessions()).isFalse()
            assertThat(registry.getSessionCount()).isZero()
            assertThat(registry.getSessionsForProject("hash-a")).isEmpty()
            assertThat(registry.getProjectCount()).isEqualTo(1)
        }
    }
}
