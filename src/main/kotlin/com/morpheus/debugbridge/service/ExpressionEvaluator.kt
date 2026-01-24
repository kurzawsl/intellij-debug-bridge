package com.morpheus.debugbridge.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.xdebugger.impl.ui.tree.nodes.XEvaluationCallbackBase
import com.morpheus.debugbridge.config.TimeoutConfig
import com.morpheus.debugbridge.model.EvaluateResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Icon

/**
 * Evaluates expressions in the current debug context.
 */
object ExpressionEvaluator {

    private val log = Logger.getInstance(ExpressionEvaluator::class.java)

    /**
     * Evaluate an expression in the current debug session
     */
    fun evaluate(expression: String): EvaluateResponse {
        val session = DebugStateService.getInstance().getActiveSession()
        if (session == null) {
            return EvaluateResponse(
                result = "",
                type = "",
                error = "No active debug session"
            )
        }

        if (!session.isSuspended) {
            return EvaluateResponse(
                result = "",
                type = "",
                error = "Debug session is not suspended"
            )
        }

        return evaluateInSession(session, expression)
    }

    /**
     * Evaluate an expression in a specific debug session.
     * This is the V2 API version that accepts an explicit session.
     */
    fun evaluateInSession(session: XDebugSession, expression: String): EvaluateResponse {
        val result = AtomicReference<EvaluateResponse>(
            EvaluateResponse(result = "", type = "", error = "Evaluation timed out")
        )
        val latch = CountDownLatch(1)

        // Evaluation must run on the EDT
        ApplicationManager.getApplication().invokeLater {
            try {
                val frame = session.currentStackFrame
                if (frame == null) {
                    result.set(EvaluateResponse(result = "", type = "", error = "No current stack frame"))
                    latch.countDown()
                    return@invokeLater
                }

                val evaluator = frame.evaluator
                if (evaluator == null) {
                    result.set(EvaluateResponse(result = "", type = "", error = "No evaluator available for current frame"))
                    latch.countDown()
                    return@invokeLater
                }

                // Evaluate using the frame's evaluator with string expression
                evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
                    override fun evaluated(value: XValue) {
                        // Extract the presentation from the XValue
                        extractValuePresentation(expression, value) { type, valueStr ->
                            result.set(EvaluateResponse(
                                result = valueStr,
                                type = type,
                                error = null
                            ))
                            latch.countDown()
                        }
                    }

                    override fun errorOccurred(errorMessage: String) {
                        log.warn("Evaluation error: $errorMessage")
                        result.set(EvaluateResponse(
                            result = "",
                            type = "",
                            error = errorMessage
                        ))
                        latch.countDown()
                    }
                }, null)

            } catch (e: Exception) {
                log.error("Failed to evaluate expression", e)
                result.set(EvaluateResponse(
                    result = "",
                    type = "",
                    error = "Evaluation failed: ${e.message}"
                ))
                latch.countDown()
            }
        }

        latch.await(TimeoutConfig.EXPRESSION_EVAL_MS, TimeUnit.MILLISECONDS)
        return result.get()
    }

    private fun extractValuePresentation(
        expression: String,
        value: XValue,
        callback: (type: String, value: String) -> Unit
    ) {
        // First try direct extraction
        val directValue = extractDirectValue(value)

        // Compute presentation asynchronously - callback will be invoked when ready
        value.computePresentation(object : XValueNode {
            override fun setPresentation(icon: Icon?, type: String?, presentedValue: String, hasChildren: Boolean) {
                val extractedType = type ?: inferType(value)
                val extractedValue = directValue ?: presentedValue
                callback(extractedType, sanitizeExpressionResult(expression, extractedValue))
            }

            override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                val extractedType = presentation.type ?: inferType(value)

                // Try to render the presentation
                val rendered = renderPresentation(presentation)
                val extractedValue = directValue ?: rendered ?: presentation.type ?: "<object>"
                callback(extractedType, sanitizeExpressionResult(expression, extractedValue))
            }

            override fun setFullValueEvaluator(fullValueEvaluator: com.intellij.xdebugger.frame.XFullValueEvaluator) {
                // This is called for long values - ignore for now as we already have a value
            }
        }, XValuePlace.TREE)
    }

    /**
     * Try to extract value directly from XValue using shared utility
     */
    private fun extractDirectValue(xValue: XValue): String? {
        return JdiValueExtractor.extractValueFromXValue(xValue)
    }

    /**
     * Infer type from XValue using shared utility
     */
    private fun inferType(xValue: XValue): String {
        return JdiValueExtractor.inferType(xValue)
    }

    /**
     * Render presentation using shared utility
     */
    private fun renderPresentation(presentation: XValuePresentation): String? {
        return JdiValueExtractor.renderPresentation(presentation)
    }

    /**
     * Sanitize evaluated expression result.
     *
     * Note: For expression evaluation, we check if the EXPRESSION itself contains
     * sensitive keywords (e.g., "getPassword()"). We cannot check variable names
     * here since we're evaluating arbitrary expressions.
     *
     * @param expression The original expression being evaluated
     * @param value The result value to sanitize
     */
    private fun sanitizeExpressionResult(expression: String, value: String): String {
        // Check if the expression references sensitive data
        val lowerExpr = expression.lowercase()
        if (SENSITIVE_EXPRESSION_PATTERNS.any { lowerExpr.contains(it) }) {
            return "***MASKED*** (expression may reference sensitive data)"
        }
        return if (value.length > 1000) value.take(1000) + "...[truncated]" else value
    }

    private val SENSITIVE_EXPRESSION_PATTERNS = listOf(
        "password", "passwd", "pwd",
        "secret", "apikey", "api_key",
        "token", "accesstoken", "access_token",
        "credential", "auth", "private"
    )
}
