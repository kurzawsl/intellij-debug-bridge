package com.morpheus.debugbridge.util

/**
 * Input validation utility for REST API parameters.
 *
 * Provides validation for common parameter types to prevent:
 * - Path traversal attacks (e.g., "../../etc/passwd")
 * - Invalid numeric ranges
 * - Malformed file paths
 * - Expression injection
 */
object InputValidator {

    /**
     * Validation result with optional error message
     */
    data class ValidationResult(
        val isValid: Boolean,
        val error: String? = null
    ) {
        companion object {
            fun success() = ValidationResult(true)
            fun failure(message: String) = ValidationResult(false, message)
        }
    }

    // Maximum allowed line number (reasonable for any source file)
    private const val MAX_LINE_NUMBER = 100_000

    // Maximum file path length
    private const val MAX_PATH_LENGTH = 1024

    // Maximum expression length (prevents DoS via huge expressions)
    private const val MAX_EXPRESSION_LENGTH = 10_000

    // Maximum variable path length for expansion
    private const val MAX_VARIABLE_PATH_LENGTH = 500

    // Dangerous path patterns that indicate path traversal attempts
    private val PATH_TRAVERSAL_PATTERNS = listOf(
        "..", // Parent directory traversal
        "//", // Double slash
        "~",  // Home directory reference
        "\u0000", // Null byte injection
        "%00", // URL-encoded null byte
        "%2e%2e", // URL-encoded ".."
        "..%2f", // Mixed encoding
        "%2f..", // Mixed encoding
    )

    /**
     * Validate a file path parameter.
     *
     * Rejects paths that:
     * - Contain path traversal sequences
     * - Exceed maximum length
     * - Are blank
     *
     * @param path The file path to validate
     * @param paramName The parameter name for error messages
     */
    fun validateFilePath(path: String?, paramName: String = "file"): ValidationResult {
        if (path.isNullOrBlank()) {
            return ValidationResult.failure("Missing required parameter: '$paramName'")
        }

        if (path.length > MAX_PATH_LENGTH) {
            return ValidationResult.failure("Parameter '$paramName' exceeds maximum length of $MAX_PATH_LENGTH characters")
        }

        val lowerPath = path.lowercase()
        for (pattern in PATH_TRAVERSAL_PATTERNS) {
            if (lowerPath.contains(pattern)) {
                return ValidationResult.failure("Invalid path: '$paramName' contains potentially dangerous pattern")
            }
        }

        return ValidationResult.success()
    }

    /**
     * Validate a line number parameter.
     *
     * Rejects line numbers that:
     * - Are null (when required)
     * - Are negative or zero
     * - Exceed maximum value
     *
     * @param line The line number to validate
     * @param paramName The parameter name for error messages
     */
    fun validateLineNumber(line: Int?, paramName: String = "line"): ValidationResult {
        if (line == null) {
            return ValidationResult.failure("Missing required parameter: '$paramName'")
        }

        if (line < 1) {
            return ValidationResult.failure("Parameter '$paramName' must be a positive integer (got: $line)")
        }

        if (line > MAX_LINE_NUMBER) {
            return ValidationResult.failure("Parameter '$paramName' exceeds maximum value of $MAX_LINE_NUMBER")
        }

        return ValidationResult.success()
    }

    /**
     * Validate a stack frame index parameter.
     *
     * @param index The frame index to validate
     * @param paramName The parameter name for error messages
     */
    fun validateFrameIndex(index: Int?, paramName: String = "index"): ValidationResult {
        if (index == null) {
            return ValidationResult.failure("Missing required parameter: '$paramName'")
        }

        if (index < 0) {
            return ValidationResult.failure("Parameter '$paramName' must be non-negative (got: $index)")
        }

        // Reasonable maximum stack depth
        if (index > 10_000) {
            return ValidationResult.failure("Parameter '$paramName' exceeds maximum value of 10000")
        }

        return ValidationResult.success()
    }

    /**
     * Validate an expression for evaluation.
     *
     * Rejects expressions that:
     * - Are blank
     * - Exceed maximum length
     *
     * Note: We do NOT reject expressions based on content since users
     * need to be able to evaluate arbitrary Java expressions. The JDI
     * sandbox handles security.
     *
     * @param expression The expression to validate
     */
    fun validateExpression(expression: String?): ValidationResult {
        if (expression.isNullOrBlank()) {
            return ValidationResult.failure("Missing required parameter: 'expression'")
        }

        if (expression.length > MAX_EXPRESSION_LENGTH) {
            return ValidationResult.failure("Expression exceeds maximum length of $MAX_EXPRESSION_LENGTH characters")
        }

        return ValidationResult.success()
    }

    /**
     * Validate a variable path for expansion.
     *
     * @param path The variable path (e.g., "obj.field.subField" or "array[0]")
     */
    fun validateVariablePath(path: String?): ValidationResult {
        if (path.isNullOrBlank()) {
            return ValidationResult.failure("Missing required parameter: 'path'")
        }

        if (path.length > MAX_VARIABLE_PATH_LENGTH) {
            return ValidationResult.failure("Variable path exceeds maximum length of $MAX_VARIABLE_PATH_LENGTH characters")
        }

        // Basic syntax check: should only contain alphanumeric, dots, brackets, underscores
        val validPattern = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*|\\[[0-9]+\\])*$")
        if (!validPattern.matches(path)) {
            return ValidationResult.failure("Invalid variable path syntax: '$path'")
        }

        return ValidationResult.success()
    }

    /**
     * Validate a run configuration name.
     *
     * @param name The configuration name
     */
    fun validateConfigName(name: String?): ValidationResult {
        // Config name is optional, so null/blank is allowed
        if (name.isNullOrBlank()) {
            return ValidationResult.success()
        }

        if (name.length > 256) {
            return ValidationResult.failure("Configuration name exceeds maximum length of 256 characters")
        }

        return ValidationResult.success()
    }
}
