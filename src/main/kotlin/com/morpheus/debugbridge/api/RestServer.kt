package com.morpheus.debugbridge.api

import com.intellij.openapi.diagnostic.Logger
import com.morpheus.debugbridge.config.TimeoutConfig
import com.morpheus.debugbridge.model.*
import com.morpheus.debugbridge.model.CreateConfigRequest
import com.morpheus.debugbridge.model.ConfigResponse
import com.morpheus.debugbridge.service.BreakpointManager
import com.morpheus.debugbridge.service.ConsoleLogService
import com.morpheus.debugbridge.service.DebugController
import com.morpheus.debugbridge.service.DebugStateService
import com.morpheus.debugbridge.service.ExpressionEvaluator
import com.morpheus.debugbridge.service.FileService
import com.morpheus.debugbridge.service.InspectionService
import com.morpheus.debugbridge.service.VariableExtractor
import com.morpheus.debugbridge.util.InputValidator
import com.morpheus.debugbridge.util.RateLimiter
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Embedded HTTP server that exposes debug state via REST API.
 * Binds to localhost:19999 (configurable).
 */
class RestServer(private val port: Int = TimeoutConfig.DEFAULT_PORT) {

    private val log = Logger.getInstance(RestServer::class.java)
    private var server: NettyApplicationEngine? = null

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Start the REST server
     */
    fun start() {
        if (server != null) {
            log.warn("REST server already running")
            return
        }

        log.info("Starting Debug Bridge REST server on port $port")

        server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            install(ContentNegotiation) {
                json(json)
            }

            install(CORS) {
                // SECURITY: Only allow specific localhost origins - this API is local-only
                // FIX: Avoid wildcard ports - explicitly list allowed ports
                allowHost("localhost:$port")
                allowHost("127.0.0.1:$port")
                // Also allow common frontend dev ports that might call this API
                for (devPort in listOf(3000, 5173, 8080)) {
                    allowHost("localhost:$devPort")
                    allowHost("127.0.0.1:$devPort")
                }
                allowHeader(HttpHeaders.ContentType)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Delete)
                allowMethod(HttpMethod.Options)
            }

            configureRouting()
        }.start(wait = false)

        log.info("Debug Bridge REST server started on http://127.0.0.1:$port")
    }

    /**
     * Stop the REST server
     */
    fun stop() {
        log.info("Stopping Debug Bridge REST server")
        server?.stop(TimeoutConfig.SERVER_STOP_GRACE_MS, TimeoutConfig.SERVER_STOP_TIMEOUT_MS)
        server = null
    }

    /**
     * Check if server is running
     */
    fun isRunning(): Boolean = server != null

    /**
     * Check rate limit and respond with 429 if exceeded.
     * Returns true if rate limited (caller should return early).
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.checkRateLimit(category: String): Boolean {
        if (!RateLimiter.isAllowed(category)) {
            val (current, max) = RateLimiter.getStatus(category)
            call.respond(
                HttpStatusCode.TooManyRequests,
                mapOf(
                    "error" to "Rate limit exceeded",
                    "category" to category,
                    "limit" to max,
                    "retryAfterMs" to 1000
                )
            )
            return true
        }
        return false
    }

    private fun Application.configureRouting() {
        routing {
            // Health check - no rate limiting
            get("/") {
                call.respond(mapOf(
                    "service" to "IntelliJ Debug Bridge",
                    "version" to "1.0.0",
                    "status" to "running"
                ))
            }

            get("/health") {
                call.respond(mapOf("status" to "ok"))
            }

            // ==================== V2 API Routes (Multi-Project Support) ====================
            // These routes explicitly target projects and sessions by ID
            route("/v2") {
                V2Routes.apply { configureV2Routes() }
            }

            // ==================== V1 API Routes (Legacy - Backward Compatible) ====================
            // These routes work with the "active" session for single-project use cases
            route("/debug") {

                // GET /debug/state - Current debug state
                // PERFORMANCE: Use ?full=true to include stack trace (slower)
                get("/state") {
                    if (checkRateLimit("read")) return@get
                    val includeFull = call.request.queryParameters["full"]?.toBoolean() ?: false
                    val state = if (includeFull) {
                        // Full state with stack trace - use when stack trace is needed
                        DebugStateService.getInstance().getStateWithStackTrace()
                    } else {
                        // Fast lightweight state - default
                        DebugStateService.getInstance().getState()
                    }
                    call.respond(state)
                }

                // GET /debug/variables - Variables at current position
                get("/variables") {
                    if (checkRateLimit("read")) return@get
                    val response = getVariables()
                    call.respond(response)
                }

                // GET /debug/configurations - List available run configurations
                get("/configurations") {
                    if (checkRateLimit("read")) return@get
                    val configs = DebugController.getRunConfigurations()
                    call.respond(mapOf("configurations" to configs))
                }

                // POST /debug/configurations - Create a new run configuration
                post("/configurations") {
                    if (checkRateLimit("control")) return@post
                    val request = call.receive<CreateConfigRequest>()
                    val response = DebugController.createRunConfiguration(request)
                    call.respond(response)
                }

                // DELETE /debug/configurations - Delete a run configuration
                delete("/configurations") {
                    if (checkRateLimit("control")) return@delete
                    val name = call.request.queryParameters["name"]
                    if (name.isNullOrBlank()) {
                        call.respond(ConfigResponse(false, "Missing 'name' parameter"))
                        return@delete
                    }
                    val response = DebugController.deleteRunConfiguration(name)
                    call.respond(response)
                }

                // GET /debug/modules - List available modules
                get("/modules") {
                    if (checkRateLimit("read")) return@get
                    val modules = DebugController.getModules()
                    call.respond(mapOf("modules" to modules))
                }

                // POST /debug/start - Start debugging
                post("/start") {
                    if (checkRateLimit("control")) return@post
                    val configName = call.request.queryParameters["config"]
                    val success = withContext(Dispatchers.IO) {
                        DebugController.startDebug(configName)
                    }
                    // PERFORMANCE: Use non-blocking delay instead of Thread.sleep
                    if (success) {
                        delay(500) // Brief delay for session init (reduced from 2000)
                        withContext(Dispatchers.IO) {
                            DebugController.waitForSuspend(3000) // Reduced timeout
                        }
                    }
                    call.respond(ApiResponse(
                        success = success,
                        message = if (success) "Debug started" else "Failed to start debug",
                        state = DebugStateService.getInstance().getState()
                    ))
                }

                // POST /debug/stop - Stop debugging
                post("/stop") {
                    if (checkRateLimit("control")) return@post
                    val success = DebugController.stopDebug()
                    call.respond(ApiResponse(
                        success = success,
                        message = if (success) "Debug stopped" else "Failed to stop debug or no active session",
                        state = DebugStateService.getInstance().getState()
                    ))
                }

                // POST /debug/evaluate - Evaluate expression
                post("/evaluate") {
                    if (checkRateLimit("evaluate")) return@post
                    val request = call.receive<EvaluateRequest>()
                    val response = evaluateExpression(request.expression)
                    call.respond(response)
                }

                // POST /debug/step-over
                // PERFORMANCE: Non-blocking with coroutine delay
                post("/step-over") {
                    if (checkRateLimit("control")) return@post
                    val success = withContext(Dispatchers.IO) { DebugController.stepOver() }
                    if (success) {
                        // Wait for suspend state using coroutine, not Thread.sleep
                        withContext(Dispatchers.IO) { DebugController.waitForSuspend(1000) }
                    }
                    call.respond(ApiResponse(
                        success = success,
                        message = if (success) "Step over executed" else "Failed to step over",
                        state = DebugStateService.getInstance().getState()
                    ))
                }

                // POST /debug/step-into
                post("/step-into") {
                    if (checkRateLimit("control")) return@post
                    val success = withContext(Dispatchers.IO) { DebugController.stepInto() }
                    if (success) {
                        withContext(Dispatchers.IO) { DebugController.waitForSuspend(1000) }
                    }
                    call.respond(ApiResponse(
                        success = success,
                        message = if (success) "Step into executed" else "Failed to step into",
                        state = DebugStateService.getInstance().getState()
                    ))
                }

                // POST /debug/step-out
                post("/step-out") {
                    if (checkRateLimit("control")) return@post
                    val success = withContext(Dispatchers.IO) { DebugController.stepOut() }
                    if (success) {
                        withContext(Dispatchers.IO) { DebugController.waitForSuspend(2000) }
                    }
                    call.respond(ApiResponse(
                        success = success,
                        message = if (success) "Step out executed" else "Failed to step out",
                        state = DebugStateService.getInstance().getState()
                    ))
                }

                // POST /debug/resume
                post("/resume") {
                    if (checkRateLimit("control")) return@post
                    val success = withContext(Dispatchers.IO) { DebugController.resume() }
                    call.respond(ApiResponse(
                        success = success,
                        message = if (success) "Resumed execution" else "Failed to resume",
                        state = DebugStateService.getInstance().getState()
                    ))
                }

                // POST /debug/pause
                post("/pause") {
                    if (checkRateLimit("control")) return@post
                    val success = withContext(Dispatchers.IO) { DebugController.pause() }
                    if (success) {
                        withContext(Dispatchers.IO) { DebugController.waitForSuspend(1000) }
                    }
                    call.respond(ApiResponse(
                        success = success,
                        message = if (success) "Paused execution" else "Failed to pause",
                        state = DebugStateService.getInstance().getState()
                    ))
                }

                // GET /debug/breakpoints - List breakpoints
                get("/breakpoints") {
                    if (checkRateLimit("read")) return@get
                    val breakpoints = getBreakpoints()
                    call.respond(BreakpointsResponse(breakpoints))
                }

                // POST /debug/breakpoints - Add breakpoint
                post("/breakpoints") {
                    if (checkRateLimit("control")) return@post
                    val request = call.receive<BreakpointRequest>()
                    val response = addBreakpoint(request)
                    call.respond(response)
                }

                // DELETE /debug/breakpoints - Remove breakpoint
                delete("/breakpoints") {
                    if (checkRateLimit("control")) return@delete
                    val request = call.receive<BreakpointRequest>()
                    val response = removeBreakpoint(request)
                    call.respond(response)
                }

                // POST /debug/breakpoints/toggle - Toggle breakpoint enabled state
                post("/breakpoints/toggle") {
                    if (checkRateLimit("control")) return@post
                    val request = call.receive<BreakpointRequest>()
                    val success = BreakpointManager.toggleBreakpoint(request.file, request.line)
                    call.respond(ApiResponse(
                        success = success,
                        message = if (success) "Breakpoint toggled at ${request.file}:${request.line}"
                                 else "Breakpoint not found"
                    ))
                }

                // DELETE /debug/breakpoints/all - Remove all breakpoints
                delete("/breakpoints/all") {
                    if (checkRateLimit("control")) return@delete
                    val count = BreakpointManager.removeAllBreakpoints()
                    call.respond(ApiResponse(
                        success = true,
                        message = "Removed $count breakpoints"
                    ))
                }

                // POST /debug/run-to-cursor - Run to specific line
                post("/run-to-cursor") {
                    if (checkRateLimit("control")) return@post
                    val file = call.request.queryParameters["file"]
                    val line = call.request.queryParameters["line"]?.toIntOrNull()

                    // Validate file path
                    val fileValidation = InputValidator.validateFilePath(file)
                    if (!fileValidation.isValid) {
                        call.respond(ApiResponse(success = false, message = fileValidation.error))
                        return@post
                    }

                    // Validate line number
                    val lineValidation = InputValidator.validateLineNumber(line)
                    if (!lineValidation.isValid) {
                        call.respond(ApiResponse(success = false, message = lineValidation.error))
                        return@post
                    }

                    val success = withContext(Dispatchers.IO) {
                        DebugController.runToCursor(file!!, line!!)
                    }
                    if (success) {
                        withContext(Dispatchers.IO) {
                            DebugController.waitForSuspend(TimeoutConfig.RUN_TO_CURSOR_MS)
                        }
                    }
                    call.respond(ApiResponse(
                        success = success,
                        message = if (success) "Running to $file:$line" else "Failed to run to cursor",
                        state = DebugStateService.getInstance().getState()
                    ))
                }

                // POST /debug/variables/expand - Expand nested variable
                post("/variables/expand") {
                    if (checkRateLimit("read")) return@post
                    val request = call.receive<ExpandVariableRequest>()
                    val response = VariableExtractor.expandVariable(request.path)
                    call.respond(response)
                }

                // GET /debug/frames - Get full stack trace with frame indices
                // PERFORMANCE: This explicitly requests stack trace (slower than /state)
                get("/frames") {
                    if (checkRateLimit("read")) return@get
                    val frames = DebugStateService.getInstance().getStateWithStackTrace().stackTrace
                    call.respond(mapOf("frames" to frames))
                }

                // POST /debug/frames/select - Select a specific stack frame
                post("/frames/select") {
                    if (checkRateLimit("control")) return@post
                    val index = call.request.queryParameters["index"]?.toIntOrNull()

                    // Validate frame index
                    val validation = InputValidator.validateFrameIndex(index)
                    if (!validation.isValid) {
                        call.respond(ApiResponse(success = false, message = validation.error))
                        return@post
                    }

                    val success = withContext(Dispatchers.IO) {
                        DebugController.selectFrame(index!!)
                    }
                    call.respond(ApiResponse(
                        success = success,
                        message = if (success) "Selected frame $index" else "Failed to select frame",
                        state = DebugStateService.getInstance().getState()
                    ))
                }

                // ========== Console Log Endpoints ==========

                // GET /debug/console - Get console output
                get("/console") {
                    if (checkRateLimit("read")) return@get
                    val lastN = call.request.queryParameters["last"]?.toIntOrNull()
                    val fromLine = call.request.queryParameters["from"]?.toIntOrNull()
                    val toLine = call.request.queryParameters["to"]?.toIntOrNull()
                    val filter = call.request.queryParameters["filter"]
                    val type = call.request.queryParameters["type"]

                    val response = if (lastN != null || fromLine != null || toLine != null || filter != null || type != null) {
                        ConsoleLogService.getLines(lastN, fromLine, toLine, filter, type)
                    } else {
                        ConsoleLogService.getLines(lastN = 100)
                    }
                    call.respond(response)
                }

                // DELETE /debug/console - Clear console buffer
                delete("/console") {
                    if (checkRateLimit("control")) return@delete
                    ConsoleLogService.clear()
                    call.respond(ApiResponse(success = true, message = "Console buffer cleared"))
                }

                // ========== Inspection/Linter Endpoints ==========

                // GET /debug/problems - Get linter/inspection problems
                get("/problems") {
                    if (checkRateLimit("read")) return@get
                    val file = call.request.queryParameters["file"]

                    val response = if (file != null) {
                        InspectionService.getProblemsForFile(file)
                    } else {
                        InspectionService.getAllOpenFileProblems()
                    }
                    call.respond(response)
                }

                // ========== File Operation Endpoints ==========

                // POST /debug/open-file - Open a file in editor
                post("/open-file") {
                    if (checkRateLimit("control")) return@post
                    val file = call.request.queryParameters["file"]
                    val line = call.request.queryParameters["line"]?.toIntOrNull()
                    val column = call.request.queryParameters["column"]?.toIntOrNull()

                    if (file.isNullOrBlank()) {
                        call.respond(FileOperationResponse(success = false, error = "Missing 'file' parameter"))
                        return@post
                    }

                    val response = FileService.openFile(file, line, column)
                    call.respond(response)
                }

                // POST /debug/close-file - Close a file
                post("/close-file") {
                    if (checkRateLimit("control")) return@post
                    val file = call.request.queryParameters["file"]

                    if (file.isNullOrBlank()) {
                        call.respond(FileOperationResponse(success = false, error = "Missing 'file' parameter"))
                        return@post
                    }

                    val response = FileService.closeFile(file)
                    call.respond(response)
                }

                // GET /debug/open-files - List open files
                get("/open-files") {
                    if (checkRateLimit("read")) return@get
                    val response = FileService.getOpenFiles()
                    call.respond(response)
                }

                // POST /debug/go-to-line - Navigate to line in current file
                post("/go-to-line") {
                    if (checkRateLimit("control")) return@post
                    val line = call.request.queryParameters["line"]?.toIntOrNull()
                    val column = call.request.queryParameters["column"]?.toIntOrNull()

                    if (line == null) {
                        call.respond(FileOperationResponse(success = false, error = "Missing or invalid 'line' parameter"))
                        return@post
                    }

                    val response = FileService.goToLine(line, column)
                    call.respond(response)
                }
            }
        }
    }

    // ========== Implementation methods ==========

    private suspend fun getVariables(): VariablesResponse {
        val session = DebugStateService.getInstance().getActiveSession()
        return VariableExtractor.extractVariables(session)
    }

    private suspend fun evaluateExpression(expression: String): EvaluateResponse {
        return ExpressionEvaluator.evaluate(expression)
    }

    private fun getBreakpoints(): List<BreakpointInfo> {
        return BreakpointManager.getAllBreakpoints()
    }

    private fun addBreakpoint(request: BreakpointRequest): ApiResponse {
        val success = BreakpointManager.addBreakpoint(
            request.file,
            request.line,
            request.condition
        )
        return ApiResponse(
            success = success,
            message = if (success) "Breakpoint added at ${request.file}:${request.line}"
                     else "Failed to add breakpoint"
        )
    }

    private fun removeBreakpoint(request: BreakpointRequest): ApiResponse {
        val success = BreakpointManager.removeBreakpoint(request.file, request.line)
        return ApiResponse(
            success = success,
            message = if (success) "Breakpoint removed from ${request.file}:${request.line}"
                     else "Failed to remove breakpoint"
        )
    }
}
