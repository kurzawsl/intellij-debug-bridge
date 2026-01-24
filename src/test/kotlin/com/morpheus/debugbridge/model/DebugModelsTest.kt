package com.morpheus.debugbridge.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Debug Models")
class DebugModelsTest {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    @Nested
    @DisplayName("DebugLocation")
    inner class DebugLocationTest {

        @Test
        fun `should serialize and deserialize correctly`() {
            val location = DebugLocation(
                file = "TelegramBot.java",
                filePath = "/src/main/java/com/example/TelegramBot.java",
                line = 42,
                method = "handleMessage",
                className = "com.example.TelegramBot"
            )

            val serialized = json.encodeToString(location)
            val deserialized = json.decodeFromString<DebugLocation>(serialized)

            assertThat(deserialized).isEqualTo(location)
            assertThat(serialized).contains("\"file\"")
            assertThat(serialized).contains("TelegramBot.java")
            assertThat(serialized).contains("\"line\"")
            assertThat(serialized).contains("42")
        }

        @Test
        fun `should handle null method and className`() {
            val location = DebugLocation(
                file = "Test.kt",
                filePath = "/test/Test.kt",
                line = 1,
                method = null,
                className = null
            )

            val serialized = json.encodeToString(location)
            val deserialized = json.decodeFromString<DebugLocation>(serialized)

            assertThat(deserialized.method).isNull()
            assertThat(deserialized.className).isNull()
        }
    }

    @Nested
    @DisplayName("StackFrameInfo")
    inner class StackFrameInfoTest {

        @Test
        fun `should serialize with all fields`() {
            val frame = StackFrameInfo(
                index = 0,
                method = "main",
                file = "Main.java",
                line = 10,
                className = "com.example.Main"
            )

            val serialized = json.encodeToString(frame)

            assertThat(serialized).contains("\"index\"")
            assertThat(serialized).contains("\"method\"")
            assertThat(serialized).contains("main")
            assertThat(serialized).contains("Main.java")
            assertThat(serialized).contains("\"line\"")
        }

        @Test
        fun `should handle null className`() {
            val frame = StackFrameInfo(
                index = 1,
                method = "lambda",
                file = "Lambda.kt",
                line = 5,
                className = null
            )

            val deserialized = json.decodeFromString<StackFrameInfo>(json.encodeToString(frame))
            assertThat(deserialized.className).isNull()
        }
    }

    @Nested
    @DisplayName("VariableInfo")
    inner class VariableInfoTest {

        @Test
        fun `should serialize primitive variable`() {
            val variable = VariableInfo(
                name = "count",
                type = "int",
                value = "42",
                hasChildren = false
            )

            val serialized = json.encodeToString(variable)

            assertThat(serialized).contains("\"name\"")
            assertThat(serialized).contains("count")
            assertThat(serialized).contains("\"type\"")
            assertThat(serialized).contains("int")
            assertThat(serialized).contains("\"value\"")
            assertThat(serialized).contains("42")
            assertThat(serialized).contains("\"hasChildren\"")
        }

        @Test
        fun `should serialize object variable with children`() {
            val variable = VariableInfo(
                name = "user",
                type = "User",
                value = "User@12345",
                hasChildren = true
            )

            val serialized = json.encodeToString(variable)
            assertThat(serialized).contains("\"hasChildren\"")
            assertThat(serialized).contains("true")
        }

        @Test
        fun `should default hasChildren to false`() {
            val variable = VariableInfo(
                name = "x",
                type = "String",
                value = "hello"
            )

            assertThat(variable.hasChildren).isFalse()
        }
    }

    @Nested
    @DisplayName("DebugState")
    inner class DebugStateTest {

        @Test
        fun `should serialize NO_SESSION state`() {
            val state = DebugState(
                status = DebugStatus.NO_SESSION,
                sessionName = null,
                location = null
            )

            val serialized = json.encodeToString(state)

            assertThat(serialized).contains("\"status\"")
            assertThat(serialized).contains("NO_SESSION")
            assertThat(serialized).contains("\"sessionName\"")
        }

        @Test
        fun `should serialize SUSPENDED state with location`() {
            val state = DebugState(
                status = DebugStatus.SUSPENDED,
                sessionName = "MyApp",
                location = DebugLocation(
                    file = "App.java",
                    filePath = "/app/App.java",
                    line = 100,
                    method = "run",
                    className = "App"
                ),
                stackTrace = listOf(
                    StackFrameInfo(0, "run", "App.java", 100, "App"),
                    StackFrameInfo(1, "main", "Main.java", 10, "Main")
                )
            )

            val serialized = json.encodeToString(state)
            val deserialized = json.decodeFromString<DebugState>(serialized)

            assertThat(deserialized.status).isEqualTo(DebugStatus.SUSPENDED)
            assertThat(deserialized.sessionName).isEqualTo("MyApp")
            assertThat(deserialized.location?.line).isEqualTo(100)
            assertThat(deserialized.stackTrace).hasSize(2)
        }

        @Test
        fun `should have empty stackTrace by default`() {
            val state = DebugState(
                status = DebugStatus.RUNNING,
                sessionName = "Test",
                location = null
            )

            assertThat(state.stackTrace).isEmpty()
        }
    }

    @Nested
    @DisplayName("DebugStatus")
    inner class DebugStatusTest {

        @Test
        fun `should have all expected values`() {
            val statuses = DebugStatus.entries

            assertThat(statuses).containsExactlyInAnyOrder(
                DebugStatus.NO_SESSION,
                DebugStatus.RUNNING,
                DebugStatus.SUSPENDED,
                DebugStatus.TERMINATED
            )
        }

        @Test
        fun `should serialize enum values correctly`() {
            DebugStatus.entries.forEach { status ->
                val state = DebugState(status = status, sessionName = null, location = null)
                val serialized = json.encodeToString(state)
                assertThat(serialized).contains("\"status\"")
                assertThat(serialized).contains(status.name)
            }
        }
    }

    @Nested
    @DisplayName("BreakpointInfo")
    inner class BreakpointInfoTest {

        @Test
        fun `should serialize enabled breakpoint`() {
            val bp = BreakpointInfo(
                file = "Main.java",
                line = 25,
                enabled = true,
                condition = null
            )

            val serialized = json.encodeToString(bp)

            assertThat(serialized).contains("\"file\"")
            assertThat(serialized).contains("Main.java")
            assertThat(serialized).contains("\"line\"")
            assertThat(serialized).contains("25")
            assertThat(serialized).contains("\"enabled\"")
        }

        @Test
        fun `should serialize conditional breakpoint`() {
            val bp = BreakpointInfo(
                file = "Loop.java",
                line = 10,
                enabled = true,
                condition = "i > 100"
            )

            val serialized = json.encodeToString(bp)
            assertThat(serialized).contains("\"condition\"")
            assertThat(serialized).contains("i > 100")
        }

        @Test
        fun `should default enabled to true`() {
            val bp = BreakpointInfo(file = "Test.java", line = 1)
            assertThat(bp.enabled).isTrue()
        }
    }

    @Nested
    @DisplayName("Request/Response models")
    inner class RequestResponseTest {

        @Test
        fun `BreakpointRequest should serialize`() {
            val request = BreakpointRequest(
                file = "Test.java",
                line = 42,
                condition = "x != null"
            )

            val serialized = json.encodeToString(request)
            val deserialized = json.decodeFromString<BreakpointRequest>(serialized)

            assertThat(deserialized).isEqualTo(request)
        }

        @Test
        fun `EvaluateRequest should serialize`() {
            val request = EvaluateRequest(expression = "user.getName()")

            val serialized = json.encodeToString(request)
            assertThat(serialized).contains("\"expression\"")
            assertThat(serialized).contains("user.getName()")
        }

        @Test
        fun `EvaluateResponse should serialize success`() {
            val response = EvaluateResponse(
                result = "John",
                type = "String",
                error = null
            )

            val serialized = json.encodeToString(response)
            assertThat(serialized).contains("\"result\"")
            assertThat(serialized).contains("John")
            assertThat(serialized).contains("\"type\"")
            assertThat(serialized).contains("String")
        }

        @Test
        fun `EvaluateResponse should serialize error`() {
            val response = EvaluateResponse(
                result = "",
                type = "",
                error = "Variable not found"
            )

            val serialized = json.encodeToString(response)
            assertThat(serialized).contains("\"error\"")
            assertThat(serialized).contains("Variable not found")
        }

        @Test
        fun `ApiResponse should serialize with state`() {
            val response = ApiResponse(
                success = true,
                message = "Step over executed",
                state = DebugState(
                    status = DebugStatus.SUSPENDED,
                    sessionName = "App",
                    location = null
                )
            )

            val serialized = json.encodeToString(response)
            assertThat(serialized).contains("\"success\"")
            assertThat(serialized).contains("true")
            assertThat(serialized).contains("\"message\"")
            assertThat(serialized).contains("Step over executed")
        }

        @Test
        fun `VariablesResponse should serialize`() {
            val response = VariablesResponse(
                locals = listOf(
                    VariableInfo("x", "int", "10"),
                    VariableInfo("y", "String", "hello")
                ),
                thisObject = null
            )

            val serialized = json.encodeToString(response)
            val deserialized = json.decodeFromString<VariablesResponse>(serialized)

            assertThat(deserialized.locals).hasSize(2)
        }

        @Test
        fun `ExpandVariableRequest should serialize`() {
            val request = ExpandVariableRequest(path = "user.address.city")

            val serialized = json.encodeToString(request)
            assertThat(serialized).contains("\"path\"")
            assertThat(serialized).contains("user.address.city")
        }

        @Test
        fun `ExpandedVariableResponse should serialize`() {
            val response = ExpandedVariableResponse(
                path = "user",
                children = listOf(
                    VariableInfo("name", "String", "John"),
                    VariableInfo("age", "int", "30")
                ),
                error = null
            )

            val serialized = json.encodeToString(response)
            val deserialized = json.decodeFromString<ExpandedVariableResponse>(serialized)

            assertThat(deserialized.children).hasSize(2)
        }

        @Test
        fun `BreakpointsResponse should serialize`() {
            val response = BreakpointsResponse(
                breakpoints = listOf(
                    BreakpointInfo("A.java", 10),
                    BreakpointInfo("B.java", 20, enabled = false)
                )
            )

            val deserialized = json.decodeFromString<BreakpointsResponse>(json.encodeToString(response))
            assertThat(deserialized.breakpoints).hasSize(2)
        }
    }
}
