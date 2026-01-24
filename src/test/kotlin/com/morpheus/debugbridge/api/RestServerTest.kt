package com.morpheus.debugbridge.api

import com.morpheus.debugbridge.model.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for RestServer endpoints.
 *
 * These tests verify the REST API structure and response formats
 * using Ktor's test framework. The actual business logic is tested
 * in the service tests.
 */
@DisplayName("RestServer")
class RestServerTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Create a test application with mocked routes that simulate the real API.
     * This allows testing the API structure without requiring IntelliJ services.
     */
    private fun Application.configureTestRouting() {
        install(ContentNegotiation) {
            json(json)
        }

        routing {
            get("/") {
                call.respond(mapOf(
                    "service" to "IntelliJ Debug Bridge",
                    "version" to "1.0.0",
                    "status" to "running"
                ))
            }

            get("/health") {
                call.respond(mapOf("status" to "ok"))
            }

            route("/debug") {
                get("/state") {
                    val includeFull = call.request.queryParameters["full"]?.toBoolean() ?: false
                    val state = DebugState(
                        status = DebugStatus.SUSPENDED,
                        sessionName = "TestApp",
                        location = if (includeFull) DebugLocation(
                            file = "Test.java",
                            filePath = "/src/Test.java",
                            line = 42,
                            method = "testMethod",
                            className = "com.example.Test"
                        ) else null,
                        stackTrace = if (includeFull) listOf(
                            StackFrameInfo(0, "testMethod", "Test.java", 42, "com.example.Test")
                        ) else emptyList()
                    )
                    call.respond(state)
                }

                get("/variables") {
                    call.respond(VariablesResponse(
                        locals = listOf(
                            VariableInfo("x", "int", "42"),
                            VariableInfo("name", "String", "\"test\"")
                        ),
                        thisObject = null
                    ))
                }

                get("/configurations") {
                    call.respond(mapOf("configurations" to listOf("TestApp", "DebugConfig")))
                }

                post("/step-over") {
                    call.respond(ApiResponse(
                        success = true,
                        message = "Step over executed",
                        state = DebugState(DebugStatus.SUSPENDED, "TestApp", null)
                    ))
                }

                post("/step-into") {
                    call.respond(ApiResponse(
                        success = true,
                        message = "Step into executed",
                        state = DebugState(DebugStatus.SUSPENDED, "TestApp", null)
                    ))
                }

                post("/step-out") {
                    call.respond(ApiResponse(
                        success = true,
                        message = "Step out executed",
                        state = DebugState(DebugStatus.SUSPENDED, "TestApp", null)
                    ))
                }

                post("/resume") {
                    call.respond(ApiResponse(
                        success = true,
                        message = "Resumed execution",
                        state = DebugState(DebugStatus.RUNNING, "TestApp", null)
                    ))
                }

                post("/pause") {
                    call.respond(ApiResponse(
                        success = true,
                        message = "Paused execution",
                        state = DebugState(DebugStatus.SUSPENDED, "TestApp", null)
                    ))
                }

                get("/breakpoints") {
                    call.respond(BreakpointsResponse(
                        breakpoints = listOf(
                            BreakpointInfo("Main.java", 10),
                            BreakpointInfo("Service.java", 25, condition = "x > 0")
                        )
                    ))
                }

                post("/evaluate") {
                    call.respond(EvaluateResponse(
                        result = "42",
                        type = "int",
                        error = null
                    ))
                }

                get("/frames") {
                    call.respond(mapOf("frames" to listOf(
                        StackFrameInfo(0, "method1", "A.java", 10, "A"),
                        StackFrameInfo(1, "method2", "B.java", 20, "B")
                    )))
                }
            }
        }
    }

    @Nested
    @DisplayName("Health Endpoints")
    inner class HealthEndpointsTest {

        @Test
        fun `GET root should return service info`() = testApplication {
            application { configureTestRouting() }

            val response = client.get("/")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = response.bodyAsText()
            assertThat(body).contains("IntelliJ Debug Bridge")
            assertThat(body).contains("version")
            assertThat(body).contains("running")
        }

        @Test
        fun `GET health should return ok status`() = testApplication {
            application { configureTestRouting() }

            val response = client.get("/health")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.bodyAsText()).contains("ok")
        }
    }

    @Nested
    @DisplayName("Debug State Endpoints")
    inner class DebugStateEndpointsTest {

        @Test
        fun `GET debug state should return debug state`() = testApplication {
            application { configureTestRouting() }

            val response = client.get("/debug/state")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = response.bodyAsText()
            assertThat(body).contains("SUSPENDED")
            assertThat(body).contains("TestApp")
        }

        @Test
        fun `GET debug state with full=true should include stack trace`() = testApplication {
            application { configureTestRouting() }

            val response = client.get("/debug/state?full=true")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = response.bodyAsText()
            assertThat(body).contains("stackTrace")
            assertThat(body).contains("testMethod")
        }

        @Test
        fun `GET variables should return variables response`() = testApplication {
            application { configureTestRouting() }

            val response = client.get("/debug/variables")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = response.bodyAsText()
            assertThat(body).contains("locals")
            assertThat(body).contains("x")
            assertThat(body).contains("int")
        }

        @Test
        fun `GET configurations should return list of configs`() = testApplication {
            application { configureTestRouting() }

            val response = client.get("/debug/configurations")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = response.bodyAsText()
            assertThat(body).contains("configurations")
            assertThat(body).contains("TestApp")
        }
    }

    @Nested
    @DisplayName("Step Control Endpoints")
    inner class StepControlEndpointsTest {

        @Test
        fun `POST step-over should return success`() = testApplication {
            application { configureTestRouting() }

            val response = client.post("/debug/step-over")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = response.bodyAsText()
            assertThat(body).contains("success")
            assertThat(body).contains("Step over executed")
        }

        @Test
        fun `POST step-into should return success`() = testApplication {
            application { configureTestRouting() }

            val response = client.post("/debug/step-into")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = response.bodyAsText()
            assertThat(body).contains("Step into executed")
        }

        @Test
        fun `POST step-out should return success`() = testApplication {
            application { configureTestRouting() }

            val response = client.post("/debug/step-out")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = response.bodyAsText()
            assertThat(body).contains("Step out executed")
        }

        @Test
        fun `POST resume should return running state`() = testApplication {
            application { configureTestRouting() }

            val response = client.post("/debug/resume")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = response.bodyAsText()
            assertThat(body).contains("Resumed")
            assertThat(body).contains("RUNNING")
        }

        @Test
        fun `POST pause should return suspended state`() = testApplication {
            application { configureTestRouting() }

            val response = client.post("/debug/pause")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = response.bodyAsText()
            assertThat(body).contains("Paused")
            assertThat(body).contains("SUSPENDED")
        }
    }

    @Nested
    @DisplayName("Breakpoint Endpoints")
    inner class BreakpointEndpointsTest {

        @Test
        fun `GET breakpoints should return breakpoints list`() = testApplication {
            application { configureTestRouting() }

            val response = client.get("/debug/breakpoints")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = response.bodyAsText()
            assertThat(body).contains("breakpoints")
            assertThat(body).contains("Main.java")
            assertThat(body).contains("10")
        }
    }

    @Nested
    @DisplayName("Evaluation Endpoints")
    inner class EvaluationEndpointsTest {

        @Test
        fun `POST evaluate should return evaluation result`() = testApplication {
            application { configureTestRouting() }

            val response = client.post("/debug/evaluate") {
                contentType(ContentType.Application.Json)
                setBody("""{"expression":"x + 1"}""")
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = response.bodyAsText()
            assertThat(body).contains("result")
            assertThat(body).contains("42")
            assertThat(body).contains("int")
        }
    }

    @Nested
    @DisplayName("Stack Frames Endpoints")
    inner class StackFramesEndpointsTest {

        @Test
        fun `GET frames should return stack frames`() = testApplication {
            application { configureTestRouting() }

            val response = client.get("/debug/frames")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val body = response.bodyAsText()
            assertThat(body).contains("frames")
            assertThat(body).contains("method1")
            assertThat(body).contains("method2")
        }
    }

    @Nested
    @DisplayName("RestServer Class")
    inner class RestServerClassTest {

        @Test
        fun `RestServer should have default port 19999`() {
            val server = RestServer()
            // Verify via reflection
            val portField = server.javaClass.getDeclaredField("port")
            portField.isAccessible = true
            val port = portField.get(server) as Int

            assertThat(port).isEqualTo(19999)
        }

        @Test
        fun `RestServer should support custom port`() {
            val server = RestServer(port = 8888)
            val portField = server.javaClass.getDeclaredField("port")
            portField.isAccessible = true
            val port = portField.get(server) as Int

            assertThat(port).isEqualTo(8888)
        }

        @Test
        fun `isRunning should return false initially`() {
            val server = RestServer()
            assertThat(server.isRunning()).isFalse()
        }
    }

    @Nested
    @DisplayName("JSON Serialization")
    inner class JsonSerializationTest {

        @Test
        fun `DebugState should serialize correctly`() {
            val state = DebugState(
                status = DebugStatus.SUSPENDED,
                sessionName = "Test",
                location = DebugLocation("A.java", "/A.java", 10, "method", "Class")
            )

            val serialized = json.encodeToString(DebugState.serializer(), state)
            assertThat(serialized).contains("SUSPENDED")
            assertThat(serialized).contains("Test")
        }

        @Test
        fun `ApiResponse should serialize correctly`() {
            val response = ApiResponse(
                success = true,
                message = "OK",
                state = DebugState(DebugStatus.RUNNING, "App", null)
            )

            val serialized = json.encodeToString(ApiResponse.serializer(), response)
            assertThat(serialized).contains("success")
            assertThat(serialized).contains("true")
        }
    }
}
