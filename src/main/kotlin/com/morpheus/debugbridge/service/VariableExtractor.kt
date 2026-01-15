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
import com.morpheus.debugbridge.model.VariableInfo
import com.morpheus.debugbridge.model.VariablesResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Icon

/**
 * Extracts variables from the current debug stack frame.
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

        if (!collectLatch.await(3, TimeUnit.SECONDS)) {
            return VariablesResponse(locals = emptyList())
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

        presentationLatch.await(5, TimeUnit.SECONDS)

        return VariablesResponse(
            locals = variables.sortedBy { it.name },
            thisObject = null
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
                    value = sanitizeValue(directValue ?: value),
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
                    value = sanitizeValue(extractedValue),
                    hasChildren = hasChildren
                ))
                latch.countDown()
            }

            override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
        }, XValuePlace.TREE)

        if (!latch.await(2, TimeUnit.SECONDS)) {
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

            // Try getDeclaredType which returns the Java type
            try {
                val getDeclaredType = descriptorClass.getMethod("getDeclaredType")
                val declaredType = getDeclaredType.invoke(descriptor) as? String
                if (!declaredType.isNullOrBlank()) {
                    return declaredType
                }
            } catch (e: Exception) { }

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
                            } catch (e: Exception) { "array" }
                        }
                        else -> null
                    }
                }
            } catch (e: Exception) { }
        } catch (e: Exception) {
            log.debug("inferTypeFromValue failed: ${e.message}")
        }
        return null
    }

    /**
     * Render XValuePresentation to a string by calling renderValue with a custom renderer
     */
    private fun renderPresentation(presentation: XValuePresentation): String? {
        try {
            val sb = StringBuilder()

            // Use reflection to create a dynamic proxy that implements XValueTextRenderer
            // This avoids compile-time dependency on specific method signatures
            val rendererClass = Class.forName("com.intellij.xdebugger.frame.presentation.XValuePresentation\$XValueTextRenderer")

            val handler = java.lang.reflect.InvocationHandler { _, method, args ->
                when (method.name) {
                    "renderValue", "renderNumericValue", "renderKeywordValue" -> {
                        if (args != null && args.isNotEmpty()) {
                            sb.append(args[0])
                        }
                    }
                    "renderStringValue" -> {
                        if (args != null && args.isNotEmpty()) {
                            sb.append("\"").append(args[0]).append("\"")
                        }
                    }
                    "renderSpecialSymbol" -> {
                        if (args != null && args.isNotEmpty()) {
                            sb.append(args[0])
                        }
                    }
                    "renderError" -> {
                        if (args != null && args.isNotEmpty()) {
                            sb.append("<error: ").append(args[0]).append(">")
                        }
                    }
                    // renderComment - ignore
                }
                null
            }

            val rendererProxy = java.lang.reflect.Proxy.newProxyInstance(
                rendererClass.classLoader,
                arrayOf(rendererClass),
                handler
            )

            // Call presentation.renderValue(renderer)
            val renderMethod = presentation.javaClass.getMethod("renderValue", rendererClass)
            renderMethod.invoke(presentation, rendererProxy)

            val result = sb.toString()
            return if (result.isNotBlank()) result else null
        } catch (e: Exception) {
            log.debug("renderPresentation failed: ${e.message}")
            return null
        }
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
     */
    private fun extractValueFromXValue(xValue: XValue): String? {
        try {
            val clazz = xValue.javaClass

            // For JavaValue, get the descriptor and then the JDI Value
            val getDescriptor = clazz.getMethod("getDescriptor")
            val descriptor = getDescriptor.invoke(xValue) ?: return null
            val descriptorClass = descriptor.javaClass

            // First, try to get the JDI Value object and extract primitive value
            try {
                val getValueMethod = descriptorClass.getMethod("getValue")
                val jdiValue = getValueMethod.invoke(descriptor)
                if (jdiValue != null) {
                    val extractedValue = extractFromJdiValue(jdiValue)
                    if (extractedValue != null) {
                        return extractedValue
                    }
                }
            } catch (e: Exception) {
                log.debug("getValue() failed: ${e.message}")
            }

            // Try getValueText which returns the formatted value string
            try {
                val getValueText = descriptorClass.getMethod("getValueText")
                val valueText = getValueText.invoke(descriptor) as? String
                if (!valueText.isNullOrBlank() && !valueText.contains("Collecting data")) {
                    return valueText
                }
            } catch (e: Exception) {
                log.debug("getValueText() failed: ${e.message}")
            }

            // Try getValueLabel and parse "name = value" format
            try {
                val getValueLabel = descriptorClass.getMethod("getValueLabel")
                val label = getValueLabel.invoke(descriptor) as? String
                if (!label.isNullOrBlank()) {
                    // Parse "varName = value" format
                    if (label.contains(" = ")) {
                        val valuePart = label.substringAfter(" = ").trim()
                        if (valuePart.isNotEmpty()) {
                            return valuePart
                        }
                    }
                    return label
                }
            } catch (e: Exception) {
                log.debug("getValueLabel() failed: ${e.message}")
            }

        } catch (e: Exception) {
            log.debug("extractValueFromXValue failed: ${e.message}")
        }
        return null
    }

    /**
     * Extract actual value from JDI Value object (com.sun.jdi.Value)
     */
    private fun extractFromJdiValue(jdiValue: Any): String? {
        val jdiClass = jdiValue.javaClass
        val className = jdiClass.name

        try {
            // For primitive values (IntegerValue, BooleanValue, etc.), call value()
            if (className.contains("PrimitiveValue") ||
                className.contains("IntegerValue") ||
                className.contains("LongValue") ||
                className.contains("BooleanValue") ||
                className.contains("DoubleValue") ||
                className.contains("FloatValue") ||
                className.contains("CharValue") ||
                className.contains("ByteValue") ||
                className.contains("ShortValue")) {
                try {
                    val valueMethod = jdiClass.getMethod("value")
                    val primitiveValue = valueMethod.invoke(jdiValue)
                    if (primitiveValue != null) {
                        return primitiveValue.toString()
                    }
                } catch (e: Exception) {
                    log.debug("Primitive value() failed: ${e.message}")
                }
            }

            // For StringReference, call value()
            if (className.contains("StringReference")) {
                try {
                    val valueMethod = jdiClass.getMethod("value")
                    val strValue = valueMethod.invoke(jdiValue) as? String
                    if (strValue != null) {
                        return "\"$strValue\""
                    }
                } catch (e: Exception) {
                    log.debug("StringReference value() failed: ${e.message}")
                }
            }

            // For ArrayReference, show length
            if (className.contains("ArrayReference")) {
                try {
                    val lengthMethod = jdiClass.getMethod("length")
                    val length = lengthMethod.invoke(jdiValue)
                    return "{length=$length}"
                } catch (e: Exception) {
                    log.debug("ArrayReference length() failed: ${e.message}")
                }
            }

            // For ObjectReference, get type name
            if (className.contains("ObjectReference")) {
                try {
                    val typeMethod = jdiClass.getMethod("type")
                    val type = typeMethod.invoke(jdiValue)
                    if (type != null) {
                        val nameMethod = type.javaClass.getMethod("name")
                        val typeName = nameMethod.invoke(type) as? String
                        if (typeName != null) {
                            return typeName.substringAfterLast(".")
                        }
                    }
                } catch (e: Exception) {
                    log.debug("ObjectReference type() failed: ${e.message}")
                }
            }

            // Fallback: use toString() but clean it up
            val valueStr = jdiValue.toString()
            if (valueStr.isNotBlank()) {
                if (valueStr.startsWith("instance of")) {
                    return valueStr.substringAfter("instance of ").substringBefore("(").trim()
                }
                return valueStr
            }
        } catch (e: Exception) {
            log.debug("extractFromJdiValue failed for $className: ${e.message}")
        }
        return null
    }

    private fun sanitizeValue(value: String): String {
        val lowerValue = value.lowercase()
        if (lowerValue.contains("password") || lowerValue.contains("secret") ||
            lowerValue.contains("token") || lowerValue.contains("api_key")) {
            return "***MASKED***"
        }
        return if (value.length > 500) value.take(500) + "...[truncated]" else value
    }
}
