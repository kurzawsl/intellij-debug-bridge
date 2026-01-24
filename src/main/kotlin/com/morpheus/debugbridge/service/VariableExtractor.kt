package com.morpheus.debugbridge.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.morpheus.debugbridge.config.TimeoutConfig
import com.morpheus.debugbridge.model.ExpandedVariableResponse
import com.morpheus.debugbridge.model.VariableInfo
import com.morpheus.debugbridge.model.VariablesResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Icon

/**
 * Extracts variables from the current debug stack frame.
 *
 * PERFORMANCE OPTIMIZATIONS:
 * - Configurable timeouts via TimeoutConfig
 * - Parallel presentation extraction
 * - Early return on partial data if timeout
 */
object VariableExtractor {

    private val log = Logger.getInstance(VariableExtractor::class.java)

    private data class CollectedVar(val name: String, val xValue: XValue)

    fun extractVariables(session: XDebugSession?): VariablesResponse {
        if (session == null || !session.isSuspended) {
            return VariablesResponse(locals = emptyList())
        }

        val frame = session.currentStackFrame ?: return VariablesResponse(locals = emptyList())

        val collectedVars = CopyOnWriteArrayList<CollectedVar>()
        val collectLatch = CountDownLatch(1)

        frame.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                for (i in 0 until children.size()) {
                    collectedVars.add(CollectedVar(children.getName(i), children.getValue(i)))
                }
                if (last) collectLatch.countDown()
            }

            override fun tooManyChildren(remaining: Int) { collectLatch.countDown() }
            override fun setAlreadySorted(alreadySorted: Boolean) {}
            override fun setErrorMessage(errorMessage: String) { collectLatch.countDown() }
            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) { collectLatch.countDown() }
            override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
        })

        // PERFORMANCE: Reduced timeout from 3s to 1.5s
        if (!collectLatch.await(TimeoutConfig.VARIABLE_COLLECT_MS, TimeUnit.MILLISECONDS)) {
            log.debug("Variable collection timeout - returning partial results")
            // FIX #5: Snapshot the list to avoid race with concurrent additions
            val snapshot = collectedVars.toList()
            if (snapshot.isNotEmpty()) {
                return extractPresentationsWithTimeout(snapshot, timedOut = true)
            }
            return VariablesResponse(
                locals = emptyList(),
                warning = "Variable collection timed out - no variables retrieved"
            )
        }

        // Safe to use directly after latch signals completion
        return extractPresentationsWithTimeout(collectedVars.toList(), timedOut = false)
    }

    /**
     * Extract presentations in parallel with a global timeout
     *
     * @param collectedVars Variables to extract presentations from
     * @param timedOut Whether variable collection already timed out (affects warning message)
     */
    private fun extractPresentationsWithTimeout(
        collectedVars: List<CollectedVar>,
        timedOut: Boolean = false
    ): VariablesResponse {
        if (collectedVars.isEmpty()) {
            return VariablesResponse(
                locals = emptyList(),
                warning = if (timedOut) "Variable collection timed out" else null
            )
        }

        val variables = CopyOnWriteArrayList<VariableInfo>()
        val presentationLatch = CountDownLatch(collectedVars.size)

        for (cv in collectedVars) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val varInfo = extractPresentation(cv.name, cv.xValue)
                    variables.add(varInfo)
                } finally {
                    presentationLatch.countDown()
                }
            }
        }

        // PERFORMANCE: Reduced timeout from 5s to 2s
        val presentationCompleted = presentationLatch.await(TimeoutConfig.VARIABLE_PRESENTATION_MS, TimeUnit.MILLISECONDS)

        // Determine warning based on timeout states
        val warning = when {
            timedOut && !presentationCompleted -> "Variable collection AND presentation extraction timed out - partial data"
            timedOut -> "Variable collection timed out - some variables may be missing"
            !presentationCompleted -> "Presentation extraction timed out - ${variables.size}/${collectedVars.size} variables retrieved"
            else -> null
        }

        return VariablesResponse(
            locals = variables.sortedBy { it.name },
            thisObject = null,
            warning = warning
        )
    }

    private fun extractPresentation(name: String, xValue: XValue): VariableInfo {
        val result = AtomicReference<VariableInfo>(
            VariableInfo(name = name, type = "unknown", value = "<pending>", hasChildren = false)
        )
        val latch = CountDownLatch(1)

        // First, try to get value directly from XValue (works for JavaValue)
        val directValue = extractValueFromXValue(xValue)

        xValue.computePresentation(object : XValueNode {
            override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                log.info("setPresentation(simple) for $name: type=$type, value=$value, directValue=$directValue")
                result.set(VariableInfo(
                    name = name,
                    type = type ?: "unknown",
                    value = sanitizeValue(name, directValue ?: value),
                    hasChildren = hasChildren
                ))
                latch.countDown()
            }

            override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                log.info("setPresentation(XValuePresentation) for $name: presentationType=${presentation.type}, presentationClass=${presentation.javaClass.name}, directValue=$directValue")

                // Try to render the presentation to a string
                val renderedValue = renderPresentation(presentation)
                log.info("Rendered value for $name: $renderedValue")

                val extractedValue = directValue
                    ?: renderedValue
                    ?: try { extractValueFromPresentation(presentation) } catch (e: Exception) { null }
                    ?: presentation.type
                    ?: "<object>"

                // Try to infer type from value or XValue if presentation.type is null
                val inferredType = presentation.type
                    ?: inferTypeFromValue(renderedValue, xValue)
                    ?: "unknown"

                result.set(VariableInfo(
                    name = name,
                    type = inferredType,
                    value = sanitizeValue(name, extractedValue),
                    hasChildren = hasChildren
                ))
                latch.countDown()
            }

            override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
        }, XValuePlace.TREE)

        if (!latch.await(TimeoutConfig.SINGLE_VARIABLE_MS, TimeUnit.MILLISECONDS)) {
            return VariableInfo(name = name, type = "unknown", value = "<timeout>", hasChildren = false)
        }

        return result.get()
    }

    /**
     * Try to infer the type from the rendered value or XValue descriptor
     */
    private fun inferTypeFromValue(renderedValue: String?, xValue: XValue): String? {
        // Try to get type from XValue descriptor
        try {
            val clazz = xValue.javaClass
            val getDescriptor = clazz.getMethod("getDescriptor")
            val descriptor = getDescriptor.invoke(xValue) ?: return null
            val descriptorClass = descriptor.javaClass

            // Try getDeclaredType which returns the Java type (may not exist on all descriptors)
            try {
                val getDeclaredType = descriptorClass.getMethod("getDeclaredType")
                val declaredType = getDeclaredType.invoke(descriptor) as? String
                if (!declaredType.isNullOrBlank()) {
                    return declaredType
                }
            } catch (e: Exception) {
                // Expected: getDeclaredType may not exist on this descriptor type
                log.trace("getDeclaredType not available: ${e.message}")
            }

            // Try to get the JDI Value and its type
            try {
                val getValueMethod = descriptorClass.getMethod("getValue")
                val jdiValue = getValueMethod.invoke(descriptor)
                if (jdiValue != null) {
                    val jdiClass = jdiValue.javaClass
                    val className = jdiClass.name

                    // Map JDI value classes to simple type names
                    return when {
                        className.contains("IntegerValue") -> "int"
                        className.contains("LongValue") -> "long"
                        className.contains("BooleanValue") -> "boolean"
                        className.contains("DoubleValue") -> "double"
                        className.contains("FloatValue") -> "float"
                        className.contains("CharValue") -> "char"
                        className.contains("ByteValue") -> "byte"
                        className.contains("ShortValue") -> "short"
                        className.contains("StringReference") -> "String"
                        className.contains("ArrayReference") -> {
                            // Try to get array element type
                            try {
                                val typeMethod = jdiClass.getMethod("type")
                                val arrayType = typeMethod.invoke(jdiValue)
                                val nameMethod = arrayType.javaClass.getMethod("name")
                                nameMethod.invoke(arrayType) as? String
                            } catch (e: Exception) {
                                // Expected: array type extraction may fail, fallback to generic "array"
                                log.trace("Array type extraction failed: ${e.message}")
                                "array"
                            }
                        }
                        else -> null
                    }
                }
            } catch (e: Exception) {
                // Expected: getValue may not exist or JDI type extraction may fail
                log.trace("JDI value type extraction failed: ${e.message}")
            }
        } catch (e: Exception) {
            log.debug("inferTypeFromValue failed: ${e.message}")
        }
        return null
    }

    /**
     * Render XValuePresentation to a string
     * FIX: Delegate to JdiValueExtractor to avoid code duplication
     */
    private fun renderPresentation(presentation: XValuePresentation): String? {
        return JdiValueExtractor.renderPresentation(presentation)
    }

    /**
     * Try to extract the actual value from XValuePresentation using various approaches
     */
    private fun extractValueFromPresentation(presentation: XValuePresentation): String {
        val type = presentation.type

        // Approach 1: Try to access myText field via reflection (common in JavaPrimitiveValuePresentation)
        try {
            val clazz = presentation.javaClass

            // Try to find fields that hold the value
            for (fieldName in listOf("myText", "myValue", "text", "value")) {
                try {
                    val field = clazz.getDeclaredField(fieldName)
                    field.isAccessible = true
                    val value = field.get(presentation)
                    if (value is String && value.isNotBlank()) {
                        return value
                    }
                } catch (e: NoSuchFieldException) {
                    // Try next
                }
            }

            // Try superclass fields too
            var superClass = clazz.superclass
            while (superClass != null && superClass != Any::class.java) {
                for (fieldName in listOf("myText", "myValue", "text", "value")) {
                    try {
                        val field = superClass.getDeclaredField(fieldName)
                        field.isAccessible = true
                        val value = field.get(presentation)
                        if (value is String && value.isNotBlank()) {
                            return value
                        }
                    } catch (e: NoSuchFieldException) {
                        // Try next
                    }
                }
                superClass = superClass.superclass
            }
        } catch (e: Exception) {
            // Reflection failed
        }

        return type ?: "<object>"
    }

    /**
     * Try to extract value directly from XValue (for JavaValue instances)
     * FIX: Delegate to JdiValueExtractor to avoid code duplication
     */
    private fun extractValueFromXValue(xValue: XValue): String? {
        return JdiValueExtractor.extractValueFromXValue(xValue)
    }

    /**
     * Check if a variable name indicates sensitive data that should be masked.
     * SECURITY: Checks the variable NAME, not the value, to avoid information leakage.
     */
    private fun isSensitiveVariableName(name: String): Boolean {
        val lowerName = name.lowercase()
        return SENSITIVE_NAME_PATTERNS.any { pattern -> lowerName.contains(pattern) }
    }

    private val SENSITIVE_NAME_PATTERNS = listOf(
        "password", "passwd", "pwd",
        "secret", "apikey", "api_key",
        "token", "accesstoken", "access_token",
        "credential", "auth", "private"
    )

    /**
     * Sanitize a variable value for display.
     * - Masks values of sensitive variables (by name)
     * - Truncates very long values
     */
    private fun sanitizeValue(name: String, value: String): String {
        // SECURITY: Mask values of sensitive variables based on NAME, not value content
        if (isSensitiveVariableName(name)) {
            return "***MASKED***"
        }
        return if (value.length > 500) value.take(500) + "...[truncated]" else value
    }

    /**
     * Expand a variable by path to get its children.
     * Path can be: "variableName", "variableName.field", "array[0]", etc.
     */
    fun expandVariable(path: String): ExpandedVariableResponse {
        val session = DebugStateService.getInstance().getActiveSession()
        if (session == null || !session.isSuspended) {
            return ExpandedVariableResponse(path = path, children = emptyList(), error = "No suspended debug session")
        }

        val frame = session.currentStackFrame
            ?: return ExpandedVariableResponse(path = path, children = emptyList(), error = "No current stack frame")

        // Parse the path to find the target XValue
        val pathParts = parsePath(path)
        if (pathParts.isEmpty()) {
            return ExpandedVariableResponse(path = path, children = emptyList(), error = "Invalid path")
        }

        // First, get all variables from the frame
        val rootVars = mutableMapOf<String, XValue>()
        val collectLatch = CountDownLatch(1)

        frame.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                for (i in 0 until children.size()) {
                    rootVars[children.getName(i)] = children.getValue(i)
                }
                if (last) collectLatch.countDown()
            }
            override fun tooManyChildren(remaining: Int) { collectLatch.countDown() }
            override fun setAlreadySorted(alreadySorted: Boolean) {}
            override fun setErrorMessage(errorMessage: String) { collectLatch.countDown() }
            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) { collectLatch.countDown() }
            override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
        })

        if (!collectLatch.await(TimeoutConfig.VARIABLE_EXPAND_MS, TimeUnit.MILLISECONDS)) {
            return ExpandedVariableResponse(path = path, children = emptyList(), error = "Timeout collecting variables")
        }

        // Navigate to the target variable
        val targetValue = navigateToValue(rootVars, pathParts)
            ?: return ExpandedVariableResponse(path = path, children = emptyList(), error = "Variable not found: $path")

        // Get children of the target value
        val children = getChildrenOfValue(targetValue)
        return ExpandedVariableResponse(path = path, children = children)
    }

    /**
     * Parse a variable path like "obj.field.subField" or "array[0].field"
     * Returns list of path segments
     */
    private fun parsePath(path: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()

        var i = 0
        while (i < path.length) {
            val c = path[i]
            when {
                c == '.' -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                }
                c == '[' -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                    // Read until ]
                    i++
                    while (i < path.length && path[i] != ']') {
                        current.append(path[i])
                        i++
                    }
                    if (current.isNotEmpty()) {
                        result.add("[${current}]")
                        current.clear()
                    }
                }
                else -> current.append(c)
            }
            i++
        }

        if (current.isNotEmpty()) {
            result.add(current.toString())
        }

        return result
    }

    /**
     * Navigate through the variable tree to find a specific value
     */
    private fun navigateToValue(rootVars: Map<String, XValue>, pathParts: List<String>): XValue? {
        if (pathParts.isEmpty()) return null

        var current: XValue? = rootVars[pathParts[0]]
        if (current == null) return null

        for (i in 1 until pathParts.size) {
            val part = pathParts[i]
            val children = getChildrenMap(current!!)

            current = if (part.startsWith("[") && part.endsWith("]")) {
                // Array index
                val index = part.substring(1, part.length - 1)
                children[index] ?: children["[$index]"]
            } else {
                children[part]
            }

            if (current == null) return null
        }

        return current
    }

    /**
     * Get children of an XValue as a map
     */
    private fun getChildrenMap(value: XValue): Map<String, XValue> {
        val children = mutableMapOf<String, XValue>()
        val latch = CountDownLatch(1)

        value.computeChildren(object : XCompositeNode {
            override fun addChildren(list: XValueChildrenList, last: Boolean) {
                for (i in 0 until list.size()) {
                    children[list.getName(i)] = list.getValue(i)
                }
                if (last) latch.countDown()
            }
            override fun tooManyChildren(remaining: Int) { latch.countDown() }
            override fun setAlreadySorted(alreadySorted: Boolean) {}
            override fun setErrorMessage(errorMessage: String) { latch.countDown() }
            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) { latch.countDown() }
            override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
        })

        latch.await(TimeoutConfig.VARIABLE_EXPAND_MS, TimeUnit.MILLISECONDS)
        return children
    }

    /**
     * Get children of an XValue as a list of VariableInfo
     */
    private fun getChildrenOfValue(value: XValue): List<VariableInfo> {
        val children = CopyOnWriteArrayList<VariableInfo>()
        val childrenMap = getChildrenMap(value)

        val presentationLatch = CountDownLatch(childrenMap.size)

        for ((name, childValue) in childrenMap) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val varInfo = extractPresentation(name, childValue)
                    children.add(varInfo)
                } finally {
                    presentationLatch.countDown()
                }
            }
        }

        presentationLatch.await(TimeoutConfig.VARIABLE_PRESENTATION_MS, TimeUnit.MILLISECONDS)
        return children.sortedBy { it.name }
    }
}
