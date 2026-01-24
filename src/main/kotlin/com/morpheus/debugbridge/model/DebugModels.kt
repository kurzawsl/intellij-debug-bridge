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
    val thisObject: ObjectInfo? = null,  // 'this' reference if available
    val warning: String? = null          // Non-null if data is partial/incomplete (e.g., timeout)
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
 * Request to expand a nested variable
 */
@Serializable
data class ExpandVariableRequest(
    val path: String  // e.g., "myObject.field.subField" or "args[0]"
)

/**
 * Response for expanded variable children
 */
@Serializable
data class ExpandedVariableResponse(
    val path: String,
    val children: List<VariableInfo>,
    val error: String? = null
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

/**
 * Request to create a run configuration
 */
@Serializable
data class CreateConfigRequest(
    val name: String,                    // Configuration name
    val type: ConfigType,                // Type of configuration
    val className: String,               // Fully qualified class name
    val methodName: String? = null,      // For single test method (optional)
    val moduleName: String? = null,      // Module name (optional, auto-detected)
    val vmOptions: String? = null,       // JVM options
    val programArguments: String? = null,// Program arguments
    val activeProfiles: String? = null,  // Spring Boot active profiles (comma-separated)
    val awsProfile: String? = null,      // AWS credential profile name
    val awsRegion: String? = null        // AWS region (e.g., eu-west-2)
)

/**
 * Type of run configuration
 */
@Serializable
enum class ConfigType {
    JUNIT_CLASS,        // Run all tests in a class
    JUNIT_METHOD,       // Run single test method
    APPLICATION,        // Run main application (generic)
    SPRING_BOOT         // Run Spring Boot application (with active profiles + AWS)
}

/**
 * Response for configuration operations
 */
@Serializable
data class ConfigResponse(
    val success: Boolean,
    val message: String? = null,
    val configName: String? = null,
    val availableConfigs: List<String>? = null
)

// ========== Console Log Models ==========

/**
 * Response for console log requests
 */
@Serializable
data class ConsoleLogResponse(
    val lines: List<Map<String, @Serializable(with = AnySerializer::class) Any>>,
    val totalLines: Int,
    val bufferStartLine: Long = 0,
    val bufferEndLine: Long = 0,
    val message: String? = null,
    val error: String? = null
)

// ========== Inspection/Linter Models ==========

/**
 * A single inspection problem
 */
@Serializable
data class InspectionProblem(
    val file: String,
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val startColumn: Int,
    val endColumn: Int,
    val severity: String,          // ERROR, WARNING, WEAK_WARNING, INFO
    val message: String,
    val inspectionId: String? = null,
    val quickFixAvailable: Boolean = false
)

/**
 * Response for inspection requests
 */
@Serializable
data class InspectionsResponse(
    val problems: List<InspectionProblem>,
    val file: String? = null,
    val filePath: String? = null,
    val totalProblems: Int = 0,
    val errorCount: Int = 0,
    val warningCount: Int = 0,
    val weakWarningCount: Int = 0,
    val infoCount: Int = 0,
    val openFileCount: Int? = null,
    val message: String? = null,
    val error: String? = null
)

// ========== File Operation Models ==========

/**
 * Response for file operations (open, close, navigate)
 */
@Serializable
data class FileOperationResponse(
    val success: Boolean,
    val message: String? = null,
    val file: String? = null,
    val filePath: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val error: String? = null
)

/**
 * Response for listing open files
 */
@Serializable
data class OpenFilesResponse(
    val files: List<Map<String, @Serializable(with = AnySerializer::class) Any>>,
    val selectedFile: String? = null,
    val totalOpen: Int = 0,
    val error: String? = null
)

/**
 * Serializer for Any type to handle mixed types in maps
 */
object AnySerializer : kotlinx.serialization.KSerializer<Any> {
    override val descriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor("Any")

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Any) {
        when (value) {
            is String -> encoder.encodeString(value)
            is Int -> encoder.encodeInt(value)
            is Long -> encoder.encodeLong(value)
            is Boolean -> encoder.encodeBoolean(value)
            is Double -> encoder.encodeDouble(value)
            is Float -> encoder.encodeFloat(value)
            else -> encoder.encodeString(value.toString())
        }
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Any {
        return decoder.decodeString()
    }
}

// ========== Multi-Project API Models (V2) ==========

/**
 * Project information for API responses
 */
@Serializable
data class ProjectInfo(
    val id: String,               // Stable project ID (SHA-256 hash of basePath)
    val name: String,             // Human-readable project name
    val path: String,             // Absolute path to project
    val isDefault: Boolean = false,
    val sessionCount: Int = 0     // Number of active debug sessions
)

/**
 * Response for GET /projects
 */
@Serializable
data class ProjectsResponse(
    val projects: List<ProjectInfo>,
    val count: Int = projects.size
)

/**
 * Session information for API responses
 */
@Serializable
data class SessionInfo(
    val id: String,               // Unique session ID (UUID)
    val name: String,             // Session name (e.g., "PaymentServiceApplication")
    val projectId: String,        // ID of project this session belongs to
    val projectName: String,      // Human-readable project name
    val status: DebugStatus,      // Current status
    val location: DebugLocation?  // Current location (if suspended)
)

/**
 * Response for GET /sessions
 */
@Serializable
data class SessionsResponse(
    val sessions: List<SessionInfo>,
    val count: Int = sessions.size
)

/**
 * Detailed session state for GET /sessions/{id}
 */
@Serializable
data class SessionDetailResponse(
    val id: String,
    val name: String,
    val projectId: String,
    val projectName: String,
    val status: DebugStatus,
    val location: DebugLocation?,
    val stackTrace: List<StackFrameInfo> = emptyList()
)

/**
 * Response for session actions (step, resume, etc.)
 */
@Serializable
data class SessionActionResponse(
    val success: Boolean,
    val message: String? = null,
    val sessionId: String? = null,
    val session: SessionDetailResponse? = null
)

/**
 * Response for starting a debug session
 */
@Serializable
data class StartDebugResponse(
    val success: Boolean,
    val message: String? = null,
    val sessionId: String? = null,
    val projectId: String? = null
)

/**
 * Project-scoped configurations response
 */
@Serializable
data class ProjectConfigurationsResponse(
    val projectId: String,
    val projectName: String,
    val configurations: List<ConfigurationInfo>
)

/**
 * Configuration info
 */
@Serializable
data class ConfigurationInfo(
    val name: String,
    val type: String,     // "SPRING_BOOT", "JUNIT_CLASS", "APPLICATION", etc.
    val isSelected: Boolean = false
)

/**
 * Project-scoped modules response
 */
@Serializable
data class ProjectModulesResponse(
    val projectId: String,
    val projectName: String,
    val modules: List<String>
)

/**
 * Project-scoped breakpoints response
 */
@Serializable
data class ProjectBreakpointsResponse(
    val projectId: String,
    val projectName: String,
    val breakpoints: List<BreakpointInfo>
)

/**
 * Error response for V2 API
 */
@Serializable
data class ErrorResponse(
    val success: Boolean = false,
    val error: String,           // Error code (e.g., "PROJECT_NOT_FOUND")
    val message: String,         // Human-readable message
    val availableProjects: List<String>? = null,
    val availableSessions: List<String>? = null
)
