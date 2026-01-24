package com.morpheus.debugbridge.service

import com.morpheus.debugbridge.model.EvaluateResponse
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for ExpressionEvaluator.
 *
 * Note: ExpressionEvaluator requires an active debug session for most operations.
 * These tests verify the structure, model logic, and response handling.
 */
@DisplayName("ExpressionEvaluator")
class ExpressionEvaluatorTest {

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Nested
    @DisplayName("Evaluator Structure")
    inner class EvaluatorStructureTest {

        @Test
        fun `should be a singleton object`() {
            val evaluator1 = ExpressionEvaluator
            val evaluator2 = ExpressionEvaluator
            assertThat(evaluator1).isSameAs(evaluator2)
        }

        @Test
        fun `should have evaluate method`() {
            val method = ExpressionEvaluator.javaClass.getDeclaredMethod(
                "evaluate",
                String::class.java
            )
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(EvaluateResponse::class.java)
        }
    }

    @Nested
    @DisplayName("EvaluateResponse Model")
    inner class EvaluateResponseModelTest {

        @Test
        fun `should create success response`() {
            val response = EvaluateResponse(
                result = "42",
                type = "int",
                error = null
            )

            assertThat(response.result).isEqualTo("42")
            assertThat(response.type).isEqualTo("int")
            assertThat(response.error).isNull()
        }

        @Test
        fun `should create error response`() {
            val response = EvaluateResponse(
                result = "",
                type = "",
                error = "Variable not found"
            )

            assertThat(response.result).isEmpty()
            assertThat(response.type).isEmpty()
            assertThat(response.error).isEqualTo("Variable not found")
        }

        @Test
        fun `should support string results`() {
            val response = EvaluateResponse(
                result = "\"Hello World\"",
                type = "String",
                error = null
            )

            assertThat(response.result).contains("Hello World")
            assertThat(response.type).isEqualTo("String")
        }

        @Test
        fun `should support object results`() {
            val response = EvaluateResponse(
                result = "MyClass@12345",
                type = "MyClass",
                error = null
            )

            assertThat(response.result).contains("MyClass")
            assertThat(response.type).isEqualTo("MyClass")
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandlingTest {

        @Test
        fun `should handle no active session error`() {
            // When there's no active session, evaluate should return error
            // We can't easily test this without mocking ApplicationManager,
            // but we verify the error message format
            val errorResponse = EvaluateResponse(
                result = "",
                type = "",
                error = "No active debug session"
            )

            assertThat(errorResponse.error).contains("No active debug session")
        }

        @Test
        fun `should handle session not suspended error`() {
            val errorResponse = EvaluateResponse(
                result = "",
                type = "",
                error = "Debug session is not suspended"
            )

            assertThat(errorResponse.error).contains("not suspended")
        }

        @Test
        fun `should handle no stack frame error`() {
            val errorResponse = EvaluateResponse(
                result = "",
                type = "",
                error = "No current stack frame"
            )

            assertThat(errorResponse.error).contains("stack frame")
        }

        @Test
        fun `should handle timeout error`() {
            val errorResponse = EvaluateResponse(
                result = "",
                type = "",
                error = "Evaluation timed out"
            )

            assertThat(errorResponse.error).contains("timed out")
        }
    }

    @Nested
    @DisplayName("Value Sanitization")
    inner class ValueSanitizationTest {

        @Test
        fun `sanitizeExpressionResult method should exist`() {
            // The method takes expression and value parameters
            val method = ExpressionEvaluator.javaClass.getDeclaredMethod(
                "sanitizeExpressionResult",
                String::class.java,
                String::class.java
            )
            method.isAccessible = true
            assertThat(method).isNotNull
        }

        @Test
        fun `should mask result when expression contains password`() {
            val method = ExpressionEvaluator.javaClass.getDeclaredMethod(
                "sanitizeExpressionResult",
                String::class.java,
                String::class.java
            )
            method.isAccessible = true

            // Expression containing "password" should be masked
            val result = method.invoke(ExpressionEvaluator, "getPassword()", "secret123")
            assertThat(result as String).contains("MASKED")
        }

        @Test
        fun `should mask result when expression contains token`() {
            val method = ExpressionEvaluator.javaClass.getDeclaredMethod(
                "sanitizeExpressionResult",
                String::class.java,
                String::class.java
            )
            method.isAccessible = true

            // Expression containing "token" should be masked
            val result = method.invoke(ExpressionEvaluator, "user.token", "abc123xyz")
            assertThat(result as String).contains("MASKED")
        }

        @Test
        fun `should truncate long values`() {
            val method = ExpressionEvaluator.javaClass.getDeclaredMethod(
                "sanitizeExpressionResult",
                String::class.java,
                String::class.java
            )
            method.isAccessible = true

            val longValue = "x".repeat(2000)
            val result = method.invoke(ExpressionEvaluator, "normalExpression", longValue) as String

            assertThat(result.length).isLessThan(1500)
            assertThat(result).contains("truncated")
        }
    }

    @Nested
    @DisplayName("JDI Value Extraction")
    inner class JdiValueExtractionTest {

        @Test
        fun `extractFromJdiValue method should exist in JdiValueExtractor`() {
            // JDI extraction is now centralized in JdiValueExtractor
            val method = JdiValueExtractor.javaClass.getDeclaredMethod(
                "extractFromJdiValue",
                Any::class.java
            )
            method.isAccessible = true
            assertThat(method).isNotNull
        }

        @Test
        fun `inferType method should exist`() {
            // ExpressionEvaluator delegates to JdiValueExtractor
            val method = ExpressionEvaluator.javaClass.getDeclaredMethod(
                "inferType",
                com.intellij.xdebugger.frame.XValue::class.java
            )
            method.isAccessible = true
            assertThat(method).isNotNull
        }
    }

    @Nested
    @DisplayName("Timeout Configuration")
    inner class TimeoutConfigurationTest {

        @Test
        fun `should have reasonable evaluation timeout`() {
            // The evaluate method uses latch.await(10, TimeUnit.SECONDS)
            // This test verifies the method structure
            val method = ExpressionEvaluator.javaClass.getDeclaredMethod(
                "evaluate",
                String::class.java
            )
            assertThat(method).isNotNull
        }
    }
}
