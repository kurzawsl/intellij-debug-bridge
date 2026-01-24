package com.morpheus.debugbridge.service

import com.intellij.xdebugger.XDebugSession
import com.morpheus.debugbridge.model.VariableInfo
import com.morpheus.debugbridge.model.VariablesResponse
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for VariableExtractor.
 *
 * Note: VariableExtractor uses reflection and IntelliJ's XDebugger APIs.
 * Many operations require actual debugger sessions. These tests verify
 * the structure and logic that can be tested with mocks.
 */
@DisplayName("VariableExtractor")
class VariableExtractorTest {

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Nested
    @DisplayName("Extractor Structure")
    inner class ExtractorStructureTest {

        @Test
        fun `should be a singleton object`() {
            val extractor1 = VariableExtractor
            val extractor2 = VariableExtractor
            assertThat(extractor1).isSameAs(extractor2)
        }

        @Test
        fun `should have extractVariables method`() {
            val method = VariableExtractor.javaClass.getDeclaredMethod(
                "extractVariables",
                XDebugSession::class.java
            )
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(VariablesResponse::class.java)
        }

        @Test
        fun `should have expandVariable method`() {
            val method = VariableExtractor.javaClass.getDeclaredMethod(
                "expandVariable",
                String::class.java
            )
            assertThat(method).isNotNull
        }
    }

    @Nested
    @DisplayName("Variable Response Structure")
    inner class VariableResponseStructureTest {

        @Test
        fun `VariablesResponse should have locals list`() {
            val response = VariablesResponse(
                locals = listOf(
                    VariableInfo("x", "int", "10"),
                    VariableInfo("y", "String", "hello")
                ),
                thisObject = null
            )

            assertThat(response.locals).hasSize(2)
            assertThat(response.locals[0].name).isEqualTo("x")
            assertThat(response.locals[1].name).isEqualTo("y")
        }

        @Test
        fun `VariablesResponse should support thisObject`() {
            val response = VariablesResponse(
                locals = emptyList(),
                thisObject = com.morpheus.debugbridge.model.ObjectInfo(
                    type = "MyClass",
                    id = "@12345",
                    fields = listOf(
                        VariableInfo("field1", "String", "value1")
                    )
                )
            )

            assertThat(response.thisObject).isNotNull
            assertThat(response.thisObject?.type).isEqualTo("MyClass")
            assertThat(response.thisObject?.fields).hasSize(1)
        }

        @Test
        fun `VariablesResponse should support warning for timeouts`() {
            val response = VariablesResponse(
                locals = listOf(VariableInfo("partial", "int", "1")),
                thisObject = null,
                warning = "Variable collection timed out - some variables may be missing"
            )

            assertThat(response.warning).isNotNull
            assertThat(response.warning).contains("timed out")
        }

        @Test
        fun `VariablesResponse warning should default to null`() {
            val response = VariablesResponse(
                locals = emptyList(),
                thisObject = null
            )

            assertThat(response.warning).isNull()
        }
    }

    @Nested
    @DisplayName("VariableInfo Structure")
    inner class VariableInfoStructureTest {

        @Test
        fun `should create variable info with all fields`() {
            val info = VariableInfo(
                name = "counter",
                type = "Integer",
                value = "42",
                hasChildren = true
            )

            assertThat(info.name).isEqualTo("counter")
            assertThat(info.type).isEqualTo("Integer")
            assertThat(info.value).isEqualTo("42")
            assertThat(info.hasChildren).isTrue()
        }

        @Test
        fun `hasChildren should default to false`() {
            val info = VariableInfo(
                name = "simple",
                type = "int",
                value = "1"
            )

            assertThat(info.hasChildren).isFalse()
        }
    }

    @Nested
    @DisplayName("Timeouts")
    inner class TimeoutsTest {

        @Test
        fun `should have reasonable timeouts for collection`() {
            // Timeouts are now centralized in TimeoutConfig
            val collectTimeout = com.morpheus.debugbridge.config.TimeoutConfig.VARIABLE_COLLECT_MS

            // Should be 1500ms or less for performance
            assertThat(collectTimeout).isLessThanOrEqualTo(1500L)
        }

        @Test
        fun `should have reasonable timeouts for presentation`() {
            // Timeouts are now centralized in TimeoutConfig
            val presentationTimeout = com.morpheus.debugbridge.config.TimeoutConfig.VARIABLE_PRESENTATION_MS

            // Should be 2000ms or less for performance
            assertThat(presentationTimeout).isLessThanOrEqualTo(2000L)
        }
    }

    @Nested
    @DisplayName("Data Extraction")
    inner class DataExtractionTest {

        @Test
        fun `should have extractVariables method that accepts nullable session`() {
            val method = VariableExtractor.javaClass.getDeclaredMethod(
                "extractVariables",
                com.intellij.xdebugger.XDebugSession::class.java
            )
            assertThat(method).isNotNull
        }

        @Test
        fun `expandVariable should return ExpandedVariableResponse type`() {
            // Verify the expandVariable method returns the correct type
            val method = VariableExtractor.javaClass.getDeclaredMethod(
                "expandVariable",
                String::class.java
            )
            assertThat(method.returnType).isEqualTo(com.morpheus.debugbridge.model.ExpandedVariableResponse::class.java)
        }
    }
}
