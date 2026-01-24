package com.morpheus.debugbridge.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.presentation.XValuePresentation

/**
 * Shared utility for extracting values from JDI (Java Debug Interface) objects.
 * Centralizes reflection-based extraction to avoid code duplication.
 *
 * This class handles the complexity of extracting actual values from IntelliJ's
 * debug infrastructure, which wraps JDI values in various presentation classes.
 */
object JdiValueExtractor {

    private val log = Logger.getInstance(JdiValueExtractor::class.java)

    /**
     * Extract a string value from a JDI Value object (com.sun.jdi.Value).
     *
     * Handles all JDI value types:
     * - Primitive values (int, long, boolean, etc.)
     * - String references
     * - Array references
     * - Object references
     *
     * @param jdiValue The JDI value object
     * @return The extracted string representation, or null if extraction fails
     */
    fun extractFromJdiValue(jdiValue: Any): String? {
        val jdiClass = jdiValue.javaClass
        val className = jdiClass.name

        try {
            // Primitive values (IntegerValue, BooleanValue, etc.) - call value()
            if (isPrimitiveValue(className)) {
                return extractPrimitiveValue(jdiValue, jdiClass)
            }

            // StringReference - call value()
            if (className.contains("StringReference")) {
                return extractStringValue(jdiValue, jdiClass)
            }

            // ArrayReference - get length
            if (className.contains("ArrayReference")) {
                return extractArrayInfo(jdiValue, jdiClass)
            }

            // ObjectReference - get type name
            if (className.contains("ObjectReference")) {
                return extractObjectTypeName(jdiValue, jdiClass)
            }

            // Fallback: use toString() with cleanup
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

    private fun isPrimitiveValue(className: String): Boolean {
        return className.contains("PrimitiveValue") ||
                className.contains("IntegerValue") ||
                className.contains("LongValue") ||
                className.contains("BooleanValue") ||
                className.contains("DoubleValue") ||
                className.contains("FloatValue") ||
                className.contains("CharValue") ||
                className.contains("ByteValue") ||
                className.contains("ShortValue")
    }

    private fun extractPrimitiveValue(jdiValue: Any, jdiClass: Class<*>): String? {
        return try {
            val valueMethod = jdiClass.getMethod("value")
            valueMethod.invoke(jdiValue)?.toString()
        } catch (e: Exception) {
            log.debug("Primitive value() failed: ${e.message}")
            null
        }
    }

    private fun extractStringValue(jdiValue: Any, jdiClass: Class<*>): String? {
        return try {
            val valueMethod = jdiClass.getMethod("value")
            val strValue = valueMethod.invoke(jdiValue) as? String
            if (strValue != null) "\"$strValue\"" else null
        } catch (e: Exception) {
            log.debug("StringReference value() failed: ${e.message}")
            null
        }
    }

    private fun extractArrayInfo(jdiValue: Any, jdiClass: Class<*>): String? {
        return try {
            val lengthMethod = jdiClass.getMethod("length")
            val length = lengthMethod.invoke(jdiValue)
            "{length=$length}"
        } catch (e: Exception) {
            log.debug("ArrayReference length() failed: ${e.message}")
            null
        }
    }

    private fun extractObjectTypeName(jdiValue: Any, jdiClass: Class<*>): String? {
        return try {
            val typeMethod = jdiClass.getMethod("type")
            val type = typeMethod.invoke(jdiValue)
            if (type != null) {
                val nameMethod = type.javaClass.getMethod("name")
                val typeName = nameMethod.invoke(type) as? String
                typeName?.substringAfterLast(".")
            } else null
        } catch (e: Exception) {
            log.debug("ObjectReference type() failed: ${e.message}")
            null
        }
    }

    /**
     * Infer the type from a JDI value's class name.
     *
     * @param jdiClassName The JDI class name (e.g., "com.sun.jdi.IntegerValue")
     * @return The simple type name (e.g., "int", "String")
     */
    fun inferTypeFromJdiClassName(jdiClassName: String): String? {
        return when {
            jdiClassName.contains("IntegerValue") -> "int"
            jdiClassName.contains("LongValue") -> "long"
            jdiClassName.contains("BooleanValue") -> "boolean"
            jdiClassName.contains("DoubleValue") -> "double"
            jdiClassName.contains("FloatValue") -> "float"
            jdiClassName.contains("CharValue") -> "char"
            jdiClassName.contains("ByteValue") -> "byte"
            jdiClassName.contains("ShortValue") -> "short"
            jdiClassName.contains("StringReference") -> "String"
            jdiClassName.contains("ArrayReference") -> "array"
            else -> null
        }
    }

    /**
     * Extract the value directly from an XValue using reflection.
     * This bypasses the async presentation API for faster access.
     *
     * @param xValue The IntelliJ XValue wrapper
     * @return The extracted value string, or null if extraction fails
     */
    fun extractValueFromXValue(xValue: XValue): String? {
        try {
            val clazz = xValue.javaClass
            val getDescriptor = clazz.getMethod("getDescriptor")
            val descriptor = getDescriptor.invoke(xValue) ?: return null
            val descriptorClass = descriptor.javaClass

            // Try getValue for JDI Value - but only if value is ready
            try {
                // Check if value is ready before accessing (avoids "Value is not yet calculated" error)
                val isValueReady = try {
                    val isValueReadyMethod = descriptorClass.getMethod("isValueReady")
                    isValueReadyMethod.invoke(descriptor) as? Boolean ?: false
                } catch (e: Exception) {
                    // If method doesn't exist, assume it might be ready
                    true
                }

                if (isValueReady) {
                    val getValueMethod = descriptorClass.getMethod("getValue")
                    val jdiValue = getValueMethod.invoke(descriptor)
                    if (jdiValue != null) {
                        return extractFromJdiValue(jdiValue)
                    }
                }
            } catch (e: Exception) {
                log.debug("getValue() failed: ${e.message}")
            }

            // Try getValueText
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
     * Render an XValuePresentation to a string using a dynamic proxy.
     *
     * @param presentation The presentation to render
     * @return The rendered string, or null if rendering fails
     */
    fun renderPresentation(presentation: XValuePresentation): String? {
        try {
            val sb = StringBuilder()
            val rendererClass = Class.forName(
                "com.intellij.xdebugger.frame.presentation.XValuePresentation\$XValueTextRenderer"
            )

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

            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                rendererClass.classLoader,
                arrayOf(rendererClass),
                handler
            )

            val renderMethod = presentation.javaClass.getMethod("renderValue", rendererClass)
            renderMethod.invoke(presentation, proxy)

            val result = sb.toString()
            return if (result.isNotBlank()) result else null
        } catch (e: Exception) {
            log.debug("renderPresentation failed: ${e.message}")
            return null
        }
    }

    /**
     * Infer the type from an XValue by examining its descriptor.
     *
     * @param xValue The XValue to inspect
     * @return The inferred type, or "unknown"
     */
    fun inferType(xValue: XValue): String {
        try {
            val clazz = xValue.javaClass
            val getDescriptor = clazz.getMethod("getDescriptor")
            val descriptor = getDescriptor.invoke(xValue) ?: return "unknown"
            val descriptorClass = descriptor.javaClass

            // Try getDeclaredType (may not exist on all descriptor types)
            try {
                val getDeclaredType = descriptorClass.getMethod("getDeclaredType")
                val type = getDeclaredType.invoke(descriptor) as? String
                if (!type.isNullOrBlank()) return type
            } catch (e: Exception) {
                // Expected: getDeclaredType may not exist on this descriptor type
                log.trace("getDeclaredType not available: ${e.message}")
            }

            // Try getValue and get type from JDI value - but only if value is ready
            try {
                // Check if value is ready before accessing
                val isValueReady = try {
                    val isValueReadyMethod = descriptorClass.getMethod("isValueReady")
                    isValueReadyMethod.invoke(descriptor) as? Boolean ?: false
                } catch (e: Exception) {
                    true // If method doesn't exist, assume it might be ready
                }

                if (isValueReady) {
                    val getValue = descriptorClass.getMethod("getValue")
                    val jdiValue = getValue.invoke(descriptor) ?: return "unknown"
                    val jdiClassName = jdiValue.javaClass.name
                    return inferTypeFromJdiClassName(jdiClassName) ?: "unknown"
                }
            } catch (e: Exception) {
                // Expected: getValue may not exist or return null
                log.trace("getValue not available for type inference: ${e.message}")
            }

        } catch (e: Exception) {
            log.debug("inferType failed: ${e.message}")
        }
        return "unknown"
    }
}
