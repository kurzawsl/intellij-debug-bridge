package com.morpheus.debugbridge.model

import kotlinx.serialization.Serializable

/**
 * Current location in code where debugger is stopped
 */
@Serializable
data class DebugLocation(
    val file: String,           // "TelegramBot.java"
    val filePath: String,       // "/src/main/java/.../TelegramBot.java"
    val line: Int,              // 86
    val method: String?,        // "handleText"
    val className: String?      // "com.morpheus.assistant.telegram.TelegramBot"
)

/**
 * Single frame in the call stack
 */
@Serializable
data class StackFrameInfo(
    val index: Int,             // 0 = top of stack
    val method: String,         // "handleText"
    val file: String,           // "TelegramBot.java"
    val line: Int,              // 86
    val className: String?      // Full class name
)

/**
 * A variable or field value
 */
@Serializable
data class VariableInfo(
    val name: String,           // "chatId"
    val type: String,           // "Long"
    val value: String,          // "123456789"
    val hasChildren: Boolean = false  // true if object with fields
)

/**
 * Variables at current debug position
 */
@Serializable
data class VariablesResponse(
    val locals: List<VariableInfo>,      // Local variables
    val thisObject: ObjectInfo? = null   // 'this' reference if available
)

/**
 * Object with its fields
 */
@Serializable
data class ObjectInfo(
    val type: String,                    // "TelegramBot"
    val id: String,                      // Object ID for reference
    val fields: List<VariableInfo>       // Object fields
)

/**
 * Overall debug state
 */
@Serializable
data class DebugState(
    val status: DebugStatus,
    val sessionName: String?,
    val location: DebugLocation?,
    val stackTrace: List<StackFrameInfo> = emptyList(),
    val activeBreakpoint: BreakpointInfo? = null
)

/**
 * Debug session status
 */
@Serializable
enum class DebugStatus {
    NO_SESSION,     // No debug session active
    RUNNING,        // Session running, not paused
    SUSPENDED,      // Paused at breakpoint or step
    TERMINATED      // Session ended
}

/**
 * Breakpoint information
 */
@Serializable
data class BreakpointInfo(
    val file: String,
    val line: Int,
    val enabled: Boolean = true,
    val condition: String? = null
)

/**
 * List of breakpoints response
 */
@Serializable
data class BreakpointsResponse(
    val breakpoints: List<BreakpointInfo>
)

/**
 * Request to add/remove breakpoint
 */
@Serializable
data class BreakpointRequest(
    val file: String,
    val line: Int,
    val condition: String? = null
)

/**
 * Request to evaluate expression
 */
@Serializable
data class EvaluateRequest(
    val expression: String
)

/**
 * Result of expression evaluation
 */
@Serializable
data class EvaluateResponse(
    val result: String,
    val type: String,
    val error: String? = null
)

/**
 * Generic API response
 */
@Serializable
data class ApiResponse(
    val success: Boolean,
    val message: String? = null,
    val state: DebugState? = null
)
