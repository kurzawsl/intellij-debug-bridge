package com.morpheus.debugbridge.api

import com.intellij.openapi.diagnostic.Logger
import com.morpheus.debugbridge.model.*
import com.morpheus.debugbridge.service.DebugStateService
import com.morpheus.debugbridge.service.VariableExtractor
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Embedded HTTP server that exposes debug state via REST API.
 * Binds to localhost:19999 (configurable).
 */
class RestServer(private val port: Int = 19999) {

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
                anyHost() // Allow all for localhost dev
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
        server?.stop(1000, 2000)
        server = null
    }

    /**
     * Check if server is running
     */
    fun isRunning(): Boolean = server != null

    private fun Application.configureRouting() {
        routing {
            // Health check
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

            // Debug state endpoints
            route("/debug") {

                // GET /debug/state - Current debug state
                get("/state") {
                    val state = DebugStateService.getInstance().getState()
                    call.respond(state)
                }

                // GET /debug/variables - Variables at current position
                get("/variables") {
                    val response = getVariables()
                    call.respond(response)
                }

                // POST /debug/evaluate - Evaluate expression
                post("/evaluate") {
                    val request = call.receive<EvaluateRequest>()
                    val response = evaluateExpression(request.expression)
                    call.respond(response)
                }

                // POST /debug/step-over
                post("/step-over") {
                    val response = executeDebugAction { it.stepOver() }
                    call.respond(response)
                }

                // POST /debug/step-into
                post("/step-into") {
                    val response = executeDebugAction { it.stepInto() }
                    call.respond(response)
                }

                // POST /debug/step-out
                post("/step-out") {
                    val response = executeDebugAction { it.stepOut() }
                    call.respond(response)
                }

                // POST /debug/resume
                post("/resume") {
                    val response = executeDebugAction { it.resume() }
                    call.respond(response)
                }

                // POST /debug/pause
                post("/pause") {
                    val response = executeDebugAction { it.pause() }
                    call.respond(response)
                }

                // GET /debug/breakpoints - List breakpoints
                get("/breakpoints") {
                    val breakpoints = getBreakpoints()
                    call.respond(BreakpointsResponse(breakpoints))
                }

                // POST /debug/breakpoints - Add breakpoint
                post("/breakpoints") {
                    val request = call.receive<BreakpointRequest>()
                    val response = addBreakpoint(request)
                    call.respond(response)
                }

                // DELETE /debug/breakpoints - Remove breakpoint
                delete("/breakpoints") {
                    val request = call.receive<BreakpointRequest>()
                    val response = removeBreakpoint(request)
                    call.respond(response)
                }
            }
        }
    }

    // ========== Implementation stubs (to be filled in feature branches) ==========

    private suspend fun getVariables(): VariablesResponse {
        val session = DebugStateService.getInstance().getActiveSession()
        return VariableExtractor.extractVariables(session)
    }

    private suspend fun evaluateExpression(expression: String): EvaluateResponse {
        // TODO: Implement expression evaluation
        return EvaluateResponse(
            result = "Not implemented yet",
            type = "Unknown",
            error = "Expression evaluation not yet implemented"
        )
    }

    private suspend fun executeDebugAction(action: (DebugActionExecutor) -> Boolean): ApiResponse {
        // TODO: Implement debug actions through IntelliJ API
        return ApiResponse(
            success = false,
            message = "Debug actions not yet fully implemented",
            state = DebugStateService.getInstance().getState()
        )
    }

    private fun getBreakpoints(): List<BreakpointInfo> {
        // TODO: Get breakpoints from all open projects
        return emptyList()
    }

    private fun addBreakpoint(request: BreakpointRequest): ApiResponse {
        // TODO: Add breakpoint through XBreakpointManager
        return ApiResponse(
            success = false,
            message = "Breakpoint management not yet implemented"
        )
    }

    private fun removeBreakpoint(request: BreakpointRequest): ApiResponse {
        // TODO: Remove breakpoint through XBreakpointManager
        return ApiResponse(
            success = false,
            message = "Breakpoint management not yet implemented"
        )
    }
}

/**
 * Interface for executing debug actions
 */
interface DebugActionExecutor {
    fun stepOver(): Boolean
    fun stepInto(): Boolean
    fun stepOut(): Boolean
    fun resume(): Boolean
    fun pause(): Boolean
}
