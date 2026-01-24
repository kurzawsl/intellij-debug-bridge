package com.morpheus.debugbridge.api

import com.intellij.execution.RunManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.morpheus.debugbridge.model.*
import com.morpheus.debugbridge.model.CreateConfigRequest
import com.morpheus.debugbridge.model.ConfigResponse
import com.morpheus.debugbridge.service.AppRegistryService
import com.morpheus.debugbridge.service.BreakpointManager
import com.morpheus.debugbridge.service.DebugController
import com.morpheus.debugbridge.service.DebugStateService
import com.morpheus.debugbridge.service.ProjectStateService
import com.morpheus.debugbridge.service.VariableExtractor
import com.morpheus.debugbridge.util.RateLimiter
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.intellij.openapi.progress.ProcessCanceledException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * V2 API routes for multi-project support.
 * These routes explicitly target projects and sessions by ID.
 */
object V2Routes {

    private val log = Logger.getInstance(V2Routes::class.java)

    /**
     * Execute a block on the EDT (Event Dispatch Thread) using suspendCancellableCoroutine.
     * This avoids deadlocks that can occur with CountDownLatch + invokeLater pattern.
     *
     * IMPORTANT: Checks cancellation state before executing the block.
     * If the coroutine is cancelled/timed out before EDT gets to run the block,
     * the block will NOT execute, preventing fire-and-forget mutations.
     */
    private suspend fun <T> runOnEdt(timeoutMs: Long = 5000L, block: () -> T): T? {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                ApplicationManager.getApplication().invokeLater {
                    // Check if continuation is still active (not cancelled/timed out)
                    // This prevents executing the block after timeout
                    if (!continuation.isActive) {
                        log.debug("EDT block skipped - continuation was cancelled")
                        return@invokeLater
                    }

                    try {
                        val result = block()
                        continuation.resume(result)
                    } catch (e: Exception) {
                        log.error("EDT execution failed: ${e.message}", e)
                        continuation.resumeWithException(e)
                    }
                }
            }
        }
    }

    /**
     * Check rate limit and respond with 429 if exceeded
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.checkRateLimit(category: String): Boolean {
        if (!RateLimiter.isAllowed(category)) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorResponse(
                    error = "RATE_LIMIT_EXCEEDED",
                    message = "Too many requests for category: $category"
                )
            )
            return true
        }
        return false
    }

    /**
     * Configure V2 routes
     */
    fun Route.configureV2Routes() {

        // ==================== Discovery Endpoints ====================

        // GET /projects - List all open projects
        get("/projects") {
            if (checkRateLimit("read")) return@get

            val registry = AppRegistryService.getInstance()
            val projects = registry.getAllProjects().map { info ->
                ProjectInfo(
                    id = info.projectId,
                    name = info.name,
                    path = info.basePath,
                    isDefault = false,
                    sessionCount = registry.getSessionsForProject(info.projectId).size
                )
            }

            call.respond(ProjectsResponse(projects = projects))
        }

        // GET /sessions - List all active debug sessions
        get("/sessions") {
            if (checkRateLimit("read")) return@get

            val registry = AppRegistryService.getInstance()
            val sessions = registry.getAllSessions().map { ref ->
                val projectInfo = registry.getProject(ref.projectId)
                val status = when {
                    ref.session.isStopped -> DebugStatus.TERMINATED
                    ref.session.isSuspended -> DebugStatus.SUSPENDED
                    else -> DebugStatus.RUNNING
                }

                SessionInfo(
                    id = ref.sessionId,
                    name = ref.sessionName,
                    projectId = ref.projectId,
                    projectName = projectInfo?.name ?: "unknown",
                    status = status,
                    location = if (ref.session.isSuspended) extractLocation(ref.session) else null
                )
            }

            call.respond(SessionsResponse(sessions = sessions))
        }

        // ==================== Session-Scoped Endpoints ====================

        route("/sessions/{sessionId}") {

            // GET /sessions/{sessionId} - Get session details
            get {
                if (checkRateLimit("read")) return@get

                val sessionId = call.parameters["sessionId"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "MISSING_SESSION_ID", message = "Session ID is required")
                )

                val registry = AppRegistryService.getInstance()
                val sessionRef = registry.getSession(sessionId)

                if (sessionRef == null) {
                    val available = registry.getAllSessions().map { it.sessionId }
                    return@get call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(
                            error = "SESSION_NOT_FOUND",
                            message = "Session $sessionId not found",
                            availableSessions = available
                        )
                    )
                }

                val projectInfo = registry.getProject(sessionRef.projectId)
                val state = DebugStateService.getInstance().getStateWithStackTraceForSession(sessionId)
                    ?: return@get call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(error = "STATE_EXTRACTION_FAILED", message = "Failed to extract session state")
                    )

                call.respond(
                    SessionDetailResponse(
                        id = sessionId,
                        name = sessionRef.sessionName,
                        projectId = sessionRef.projectId,
                        projectName = projectInfo?.name ?: "unknown",
                        status = state.status,
                        location = state.location,
                        stackTrace = state.stackTrace
                    )
                )
            }

            // GET /sessions/{sessionId}/variables - Get variables for session
            get("/variables") {
                if (checkRateLimit("read")) return@get

                val sessionId = call.parameters["sessionId"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "MISSING_SESSION_ID", message = "Session ID is required")
                )

                val session = AppRegistryService.getInstance().getDebugSession(sessionId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(error = "SESSION_NOT_FOUND", message = "Session $sessionId not found")
                    )

                val response = VariableExtractor.extractVariables(session)
                call.respond(response)
            }

            // POST /sessions/{sessionId}/step-over
            post("/step-over") {
                if (checkRateLimit("control")) return@post
                handleSessionAction(call, "step-over") { session ->
                    session.stepOver(false)
                }
            }

            // POST /sessions/{sessionId}/step-into
            post("/step-into") {
                if (checkRateLimit("control")) return@post
                handleSessionAction(call, "step-into") { session ->
                    session.stepInto()
                }
            }

            // POST /sessions/{sessionId}/step-out
            post("/step-out") {
                if (checkRateLimit("control")) return@post
                handleSessionAction(call, "step-out") { session ->
                    session.stepOut()
                }
            }

            // POST /sessions/{sessionId}/resume
            post("/resume") {
                if (checkRateLimit("control")) return@post
                handleSessionAction(call, "resume", requireSuspended = false) { session ->
                    session.resume()
                }
            }

            // POST /sessions/{sessionId}/pause
            post("/pause") {
                if (checkRateLimit("control")) return@post
                handleSessionAction(call, "pause", requireSuspended = false) { session ->
                    session.pause()
                }
            }

            // DELETE /sessions/{sessionId} - Stop debug session
            delete {
                if (checkRateLimit("control")) return@delete

                val sessionId = call.parameters["sessionId"] ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "MISSING_SESSION_ID", message = "Session ID is required")
                )

                val session = AppRegistryService.getInstance().getDebugSession(sessionId)
                    ?: return@delete call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(error = "SESSION_NOT_FOUND", message = "Session $sessionId not found")
                    )

                withContext(Dispatchers.IO) {
                    ApplicationManager.getApplication().invokeLater {
                        session.stop()
                    }
                }

                call.respond(
                    SessionActionResponse(
                        success = true,
                        message = "Debug session stopped",
                        sessionId = sessionId
                    )
                )
            }

            // POST /sessions/{sessionId}/evaluate
            post("/evaluate") {
                if (checkRateLimit("evaluate")) return@post

                val sessionId = call.parameters["sessionId"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "MISSING_SESSION_ID", message = "Session ID is required")
                )

                val session = AppRegistryService.getInstance().getDebugSession(sessionId)
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(error = "SESSION_NOT_FOUND", message = "Session $sessionId not found")
                    )

                if (!session.isSuspended) {
                    return@post call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(error = "SESSION_NOT_SUSPENDED", message = "Session must be suspended to evaluate")
                    )
                }

                val request = call.receive<EvaluateRequest>()
                val response = com.morpheus.debugbridge.service.ExpressionEvaluator.evaluateInSession(
                    session, request.expression
                )
                call.respond(response)
            }
        }

        // ==================== Project-Scoped Endpoints ====================

        route("/projects/{projectId}") {

            // GET /projects/{projectId} - Get project details
            get {
                if (checkRateLimit("read")) return@get

                val projectId = call.parameters["projectId"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "MISSING_PROJECT_ID", message = "Project ID is required")
                )

                val registry = AppRegistryService.getInstance()
                val projectInfo = registry.getProject(projectId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(
                            error = "PROJECT_NOT_FOUND",
                            message = "Project $projectId not found",
                            availableProjects = registry.getAllProjects().map { it.projectId }
                        )
                    )

                call.respond(
                    ProjectInfo(
                        id = projectInfo.projectId,
                        name = projectInfo.name,
                        path = projectInfo.basePath,
                        sessionCount = registry.getSessionsForProject(projectId).size
                    )
                )
            }

            // GET /projects/{projectId}/sessions - List sessions for this project
            get("/sessions") {
                if (checkRateLimit("read")) return@get

                val projectId = call.parameters["projectId"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "MISSING_PROJECT_ID", message = "Project ID is required")
                )

                val registry = AppRegistryService.getInstance()
                val projectInfo = registry.getProject(projectId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(error = "PROJECT_NOT_FOUND", message = "Project $projectId not found")
                    )

                val sessions = registry.getSessionsForProject(projectId).map { ref ->
                    val status = when {
                        ref.session.isStopped -> DebugStatus.TERMINATED
                        ref.session.isSuspended -> DebugStatus.SUSPENDED
                        else -> DebugStatus.RUNNING
                    }

                    SessionInfo(
                        id = ref.sessionId,
                        name = ref.sessionName,
                        projectId = ref.projectId,
                        projectName = projectInfo.name,
                        status = status,
                        location = if (ref.session.isSuspended) extractLocation(ref.session) else null
                    )
                }

                call.respond(SessionsResponse(sessions = sessions))
            }

            // GET /projects/{projectId}/configurations - List run configurations
            get("/configurations") {
                if (checkRateLimit("read")) return@get

                val projectId = call.parameters["projectId"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "MISSING_PROJECT_ID", message = "Project ID is required")
                )

                val registry = AppRegistryService.getInstance()
                val projectInfo = registry.getProject(projectId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(error = "PROJECT_NOT_FOUND", message = "Project $projectId not found")
                    )

                val configs = runOnEdt {
                    val runManager = RunManager.getInstance(projectInfo.project)
                    val selectedConfig = runManager.selectedConfiguration
                    runManager.allSettings.map { settings ->
                        ConfigurationInfo(
                            name = settings.name,
                            type = settings.type.displayName,
                            isSelected = settings == selectedConfig
                        )
                    }
                } ?: emptyList()

                call.respond(
                    ProjectConfigurationsResponse(
                        projectId = projectId,
                        projectName = projectInfo.name,
                        configurations = configs
                    )
                )
            }

            // POST /projects/{projectId}/configurations - Create run configuration in this project
            post("/configurations") {
                if (checkRateLimit("control")) return@post

                val projectId = call.parameters["projectId"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "MISSING_PROJECT_ID", message = "Project ID is required")
                )

                val registry = AppRegistryService.getInstance()
                val projectInfo = registry.getProject(projectId)
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(error = "PROJECT_NOT_FOUND", message = "Project $projectId not found")
                    )

                val request = call.receive<CreateConfigRequest>()
                val response = runOnEdt {
                    DebugController.createRunConfigurationInProject(projectInfo.project, request)
                }

                if (response != null) {
                    call.respond(response)
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ConfigResponse(success = false, message = "Timeout creating configuration")
                    )
                }
            }

            // GET /projects/{projectId}/modules - List modules
            get("/modules") {
                if (checkRateLimit("read")) return@get

                val projectId = call.parameters["projectId"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "MISSING_PROJECT_ID", message = "Project ID is required")
                )

                val registry = AppRegistryService.getInstance()
                val projectInfo = registry.getProject(projectId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(error = "PROJECT_NOT_FOUND", message = "Project $projectId not found")
                    )

                val modules = runOnEdt {
                    ModuleManager.getInstance(projectInfo.project).modules.map { it.name }
                } ?: emptyList()

                call.respond(
                    ProjectModulesResponse(
                        projectId = projectId,
                        projectName = projectInfo.name,
                        modules = modules
                    )
                )
            }

            // GET /projects/{projectId}/breakpoints - List breakpoints in project
            get("/breakpoints") {
                if (checkRateLimit("read")) return@get

                val projectId = call.parameters["projectId"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "MISSING_PROJECT_ID", message = "Project ID is required")
                )

                val registry = AppRegistryService.getInstance()
                val projectInfo = registry.getProject(projectId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(error = "PROJECT_NOT_FOUND", message = "Project $projectId not found")
                    )

                val breakpoints = runOnEdt {
                    val bpManager = XDebuggerManager.getInstance(projectInfo.project).breakpointManager
                    bpManager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>().mapNotNull { bp ->
                        val file = bp.sourcePosition?.file ?: return@mapNotNull null
                        val line = bp.sourcePosition?.line?.plus(1) ?: (bp.line + 1)
                        BreakpointInfo(
                            file = file.name,
                            line = line,
                            enabled = bp.isEnabled,
                            condition = bp.conditionExpression?.expression
                        )
                    }
                } ?: emptyList()

                call.respond(
                    ProjectBreakpointsResponse(
                        projectId = projectId,
                        projectName = projectInfo.name,
                        breakpoints = breakpoints
                    )
                )
            }

            // POST /projects/{projectId}/breakpoints - Add breakpoint
            post("/breakpoints") {
                if (checkRateLimit("control")) return@post

                val projectId = call.parameters["projectId"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "MISSING_PROJECT_ID", message = "Project ID is required")
                )

                val registry = AppRegistryService.getInstance()
                val projectInfo = registry.getProject(projectId)
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(error = "PROJECT_NOT_FOUND", message = "Project $projectId not found")
                    )

                val request = call.receive<BreakpointRequest>()
                // Use project-scoped breakpoint addition
                val success = BreakpointManager.addBreakpointToProject(
                    projectInfo.project, request.file, request.line, request.condition
                )

                call.respond(
                    ApiResponse(
                        success = success,
                        message = if (success) "Breakpoint added at ${request.file}:${request.line}"
                        else "Failed to add breakpoint"
                    )
                )
            }

            // DELETE /projects/{projectId}/breakpoints - Remove breakpoint
            delete("/breakpoints") {
                if (checkRateLimit("control")) return@delete

                val projectId = call.parameters["projectId"] ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "MISSING_PROJECT_ID", message = "Project ID is required")
                )

                val registry = AppRegistryService.getInstance()
                registry.getProject(projectId)
                    ?: return@delete call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(error = "PROJECT_NOT_FOUND", message = "Project $projectId not found")
                    )

                val request = call.receive<BreakpointRequest>()
                val success = BreakpointManager.removeBreakpoint(request.file, request.line)

                call.respond(
                    ApiResponse(
                        success = success,
                        message = if (success) "Breakpoint removed from ${request.file}:${request.line}"
                        else "Failed to remove breakpoint"
                    )
                )
            }

            // POST /projects/{projectId}/debug/start - Start debugging in this project
            post("/debug/start") {
                if (checkRateLimit("control")) return@post

                val projectId = call.parameters["projectId"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "MISSING_PROJECT_ID", message = "Project ID is required")
                )

                val configName = call.request.queryParameters["config"]

                val registry = AppRegistryService.getInstance()
                val projectInfo = registry.getProject(projectId)
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(error = "PROJECT_NOT_FOUND", message = "Project $projectId not found")
                    )

                val success = withContext(Dispatchers.IO) {
                    DebugController.startDebugInProject(projectInfo.project, configName)
                }

                if (success) {
                    delay(500) // Brief delay for session init
                    withContext(Dispatchers.IO) {
                        DebugController.waitForSuspend(3000)
                    }
                }

                // Find the newly created session
                val newSession = registry.getSessionsForProject(projectId).maxByOrNull { it.createdAt }

                call.respond(
                    StartDebugResponse(
                        success = success,
                        message = if (success) "Debug session started" else "Failed to start debug",
                        sessionId = newSession?.sessionId,
                        projectId = projectId
                    )
                )
            }
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Extract location from a debug session
     */
    private fun extractLocation(session: com.intellij.xdebugger.XDebugSession): DebugLocation? {
        val frame = session.currentStackFrame ?: return null
        val sourcePosition = frame.sourcePosition ?: return null
        val file = sourcePosition.file

        return DebugLocation(
            file = file.name,
            filePath = file.path,
            line = sourcePosition.line + 1,
            method = null, // Would need reflection to get this
            className = null
        )
    }

    /**
     * Handle a session action (step, resume, etc.)
     * Uses runOnEdt to avoid deadlocks with EDT.
     *
     * IMPORTANT: Re-validates session inside EDT block to prevent TOCTOU race condition.
     * Between initial fetch and EDT execution, the session could be terminated by another thread.
     */
    private suspend fun handleSessionAction(
        call: ApplicationCall,
        actionName: String,
        requireSuspended: Boolean = true,
        action: (com.intellij.xdebugger.XDebugSession) -> Unit
    ) {
        val sessionId = call.parameters["sessionId"] ?: return call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(error = "MISSING_SESSION_ID", message = "Session ID is required")
        )

        val registry = AppRegistryService.getInstance()

        // Execute action on EDT with validation inside the EDT block.
        // Note: We don't do a fast-path check here because the re-validation
        // inside EDT is the only safe place to check session existence.
        // Any check outside EDT is subject to TOCTOU race conditions.
        // The session could be terminated between our initial check and EDT execution.
        val result: ActionResult = runOnEdt(5000L) {
            // Re-fetch session inside EDT block to ensure it's still valid
            val currentSessionRef = registry.getSession(sessionId)
            if (currentSessionRef == null) {
                return@runOnEdt ActionResult.NotFound(sessionId)
            }

            val session = currentSessionRef.session

            // Check suspended state inside EDT for atomicity
            if (requireSuspended && !session.isSuspended) {
                return@runOnEdt ActionResult.Error("Session must be suspended to perform $actionName")
            }

            // Check if session is stopped
            if (session.isStopped) {
                return@runOnEdt ActionResult.Error("Session is already stopped")
            }

            try {
                action(session)
                // Mark session as touched INSIDE EDT block to prevent TOCTOU race.
                // If we called touchSession outside, the session could be terminated
                // between when EDT block returns and when touchSession is called.
                registry.touchSession(sessionId)
                ActionResult.Success
            } catch (e: ProcessCanceledException) {
                // Log full exception for debugging, return generic message
                log.warn("Action $actionName was cancelled for session $sessionId", e)
                ActionResult.Cancelled("Action was cancelled")
            } catch (e: IllegalStateException) {
                // Log full exception with stack trace for debugging
                log.error("Invalid state for action $actionName on session $sessionId: ${e.message}", e)
                ActionResult.Error("Invalid session state for $actionName")
            } catch (e: Exception) {
                // Log full exception with stack trace for debugging - NEVER swallow exceptions
                log.error("Failed to execute action $actionName on session $sessionId", e)
                // Include exception class name for better debugging (not full message to avoid leaking internals)
                ActionResult.Error("Failed to execute $actionName: ${e::class.simpleName}")
            }
        } ?: ActionResult.Timeout

        // NOTE: We do NOT wait for suspend here. That was a blocking call (Thread.sleep)
        // that would exhaust the IO thread pool under load.
        // The client should poll GET /sessions/{id} to check if suspended.
        // For real-time updates, consider implementing WebSocket/SSE in the future.

        // Get updated state - re-fetch session since it may have changed
        val currentSession = registry.getSession(sessionId)
        val projectInfo = currentSession?.let { registry.getProject(it.projectId) }
        val state = DebugStateService.getInstance().getStateForSession(sessionId)

        val detailResponse = if (state != null && currentSession != null) {
            SessionDetailResponse(
                id = sessionId,
                name = currentSession.sessionName,
                projectId = currentSession.projectId,
                projectName = projectInfo?.name ?: "unknown",
                status = state.status,
                location = state.location,
                stackTrace = emptyList()
            )
        } else null

        when (result) {
            ActionResult.Success -> {
                call.respond(
                    SessionActionResponse(
                        success = true,
                        message = "$actionName executed",
                        sessionId = sessionId,
                        session = detailResponse
                    )
                )
            }
            ActionResult.Timeout -> {
                call.respond(
                    HttpStatusCode.GatewayTimeout,
                    SessionActionResponse(
                        success = false,
                        message = "Action timed out after 5 seconds",
                        sessionId = sessionId,
                        session = detailResponse
                    )
                )
            }
            is ActionResult.NotFound -> {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(
                        error = "SESSION_NOT_FOUND",
                        message = "Session ${result.sessionId} not found",
                        availableSessions = registry.getAllSessions().map { it.sessionId }
                    )
                )
            }
            is ActionResult.Cancelled -> {
                call.respond(
                    SessionActionResponse(
                        success = false,
                        message = result.reason,
                        sessionId = sessionId,
                        session = detailResponse
                    )
                )
            }
            is ActionResult.Error -> {
                // Determine appropriate HTTP status based on error message
                val httpStatus = when {
                    result.message.contains("suspended") -> HttpStatusCode.Conflict
                    result.message.contains("terminated") -> HttpStatusCode.Gone
                    result.message.contains("stopped") -> HttpStatusCode.Gone
                    else -> HttpStatusCode.InternalServerError
                }
                call.respond(
                    httpStatus,
                    SessionActionResponse(
                        success = false,
                        message = result.message,
                        sessionId = sessionId,
                        session = detailResponse
                    )
                )
            }
        }
    }

    /**
     * Result type for session actions - provides better error reporting
     */
    private sealed class ActionResult {
        object Success : ActionResult()
        object Timeout : ActionResult()
        data class NotFound(val sessionId: String) : ActionResult()
        data class Cancelled(val reason: String) : ActionResult()
        data class Error(val message: String) : ActionResult()
    }
}
