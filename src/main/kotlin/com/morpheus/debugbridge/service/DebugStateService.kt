package com.morpheus.debugbridge.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.morpheus.debugbridge.model.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-level service that tracks debug state across all projects.
 * This is a singleton - one instance for the entire IDE.
 */
@Service(Service.Level.APP)
class DebugStateService {

    private val log = Logger.getInstance(DebugStateService::class.java)

    // Track sessions by project name
    private val sessions = ConcurrentHashMap<String, XDebugSession>()

    // Current active session (most recently paused)
    @Volatile
    private var activeSession: XDebugSession? = null

    // Cached debug state for quick access
    @Volatile
    private var cachedState: DebugState = DebugState(
        status = DebugStatus.NO_SESSION,
        sessionName = null,
        location = null
    )

    companion object {
        fun getInstance(): DebugStateService {
            return ApplicationManager.getApplication().getService(DebugStateService::class.java)
        }
    }

    /**
     * Called when a debug session starts
     */
    fun onSessionStarted(session: XDebugSession) {
        val name = session.sessionName
        log.info("Debug session started: $name")
        sessions[name] = session
        activeSession = session
        updateState(DebugStatus.RUNNING, session)
    }

    /**
     * Called when a debug session is paused (breakpoint hit, step completed)
     */
    fun onSessionPaused(session: XDebugSession) {
        val name = session.sessionName
        log.info("Debug session paused: $name")
        activeSession = session
        updateState(DebugStatus.SUSPENDED, session)
    }

    /**
     * Called when a debug session resumes
     */
    fun onSessionResumed(session: XDebugSession) {
        val name = session.sessionName
        log.info("Debug session resumed: $name")
        updateState(DebugStatus.RUNNING, session)
    }

    /**
     * Called when a debug session stops
     */
    fun onSessionStopped(session: XDebugSession) {
        val name = session.sessionName
        log.info("Debug session stopped: $name")
        sessions.remove(name)

        if (activeSession == session) {
            activeSession = sessions.values.firstOrNull()
        }

        if (sessions.isEmpty()) {
            cachedState = DebugState(
                status = DebugStatus.NO_SESSION,
                sessionName = null,
                location = null
            )
        } else {
            activeSession?.let { updateState(DebugStatus.RUNNING, it) }
        }
    }

    /**
     * Get the current debug state
     */
    fun getState(): DebugState = cachedState

    /**
     * Get the active debug session (if any)
     */
    fun getActiveSession(): XDebugSession? = activeSession

    /**
     * Check if we have an active suspended session
     */
    fun isSuspended(): Boolean = cachedState.status == DebugStatus.SUSPENDED

    private fun updateState(status: DebugStatus, session: XDebugSession) {
        val location = extractLocation(session)
        val stackTrace = extractStackTrace(session)

        cachedState = DebugState(
            status = status,
            sessionName = session.sessionName,
            location = location,
            stackTrace = stackTrace
        )
    }

    private fun extractLocation(session: XDebugSession): DebugLocation? {
        val frame = session.currentStackFrame ?: return null
        val sourcePosition = frame.sourcePosition ?: return null
        val file = sourcePosition.file

        return DebugLocation(
            file = file.name,
            filePath = file.path,
            line = sourcePosition.line + 1, // Convert 0-based to 1-based
            method = extractMethodName(frame),
            className = extractClassName(frame)
        )
    }

    private fun extractStackTrace(session: XDebugSession): List<StackFrameInfo> {
        val suspendContext = session.suspendContext ?: return emptyList()
        val executionStack = suspendContext.activeExecutionStack ?: return emptyList()

        val frames = mutableListOf<StackFrameInfo>()

        // XExecutionStack.computeStackFrames is async, we need a callback
        // For now, return just the current frame; full stack impl comes later
        session.currentStackFrame?.let { frame ->
            frame.sourcePosition?.let { pos ->
                frames.add(
                    StackFrameInfo(
                        index = 0,
                        method = extractMethodName(frame) ?: "unknown",
                        file = pos.file.name,
                        line = pos.line + 1,
                        className = extractClassName(frame)
                    )
                )
            }
        }

        return frames
    }

    private fun extractMethodName(frame: com.intellij.xdebugger.frame.XStackFrame): String? {
        try {
            // For JavaStackFrame, get the descriptor which has method info
            val frameClass = frame.javaClass

            // Try getDescriptor method (for JavaStackFrame)
            try {
                val getDescriptor = frameClass.getMethod("getDescriptor")
                val descriptor = getDescriptor.invoke(frame)
                if (descriptor != null) {
                    val descriptorClass = descriptor.javaClass

                    // Try to get the method name from the descriptor
                    try {
                        val getMethod = descriptorClass.getMethod("getMethod")
                        val method = getMethod.invoke(descriptor)
                        if (method != null) {
                            val nameMethod = method.javaClass.getMethod("name")
                            return nameMethod.invoke(method) as? String
                        }
                    } catch (e: Exception) { }

                    // Try getName if getMethod fails
                    try {
                        val getName = descriptorClass.getMethod("getName")
                        return getName.invoke(descriptor) as? String
                    } catch (e: Exception) { }
                }
            } catch (e: Exception) { }

            // Fallback: try to get from equalityObject which often contains location info
            try {
                val getEqualityObject = frameClass.getMethod("getEqualityObject")
                val eqObj = getEqualityObject.invoke(frame)
                if (eqObj != null) {
                    // The equality object for JavaStackFrame is usually a Location
                    val locationClass = eqObj.javaClass
                    try {
                        val methodMethod = locationClass.getMethod("method")
                        val method = methodMethod.invoke(eqObj)
                        if (method != null) {
                            val nameMethod = method.javaClass.getMethod("name")
                            return nameMethod.invoke(method) as? String
                        }
                    } catch (e: Exception) { }
                }
            } catch (e: Exception) { }

        } catch (e: Exception) {
            log.debug("extractMethodName failed: ${e.message}")
        }
        return null
    }

    private fun extractClassName(frame: com.intellij.xdebugger.frame.XStackFrame): String? {
        try {
            val frameClass = frame.javaClass

            // Try getDescriptor method (for JavaStackFrame)
            try {
                val getDescriptor = frameClass.getMethod("getDescriptor")
                val descriptor = getDescriptor.invoke(frame)
                if (descriptor != null) {
                    val descriptorClass = descriptor.javaClass

                    // Try to get the declaring type from the method
                    try {
                        val getMethod = descriptorClass.getMethod("getMethod")
                        val method = getMethod.invoke(descriptor)
                        if (method != null) {
                            val declaringType = method.javaClass.getMethod("declaringType")
                            val type = declaringType.invoke(method)
                            if (type != null) {
                                val nameMethod = type.javaClass.getMethod("name")
                                return nameMethod.invoke(type) as? String
                            }
                        }
                    } catch (e: Exception) { }
                }
            } catch (e: Exception) { }

            // Fallback: try to get from equalityObject
            try {
                val getEqualityObject = frameClass.getMethod("getEqualityObject")
                val eqObj = getEqualityObject.invoke(frame)
                if (eqObj != null) {
                    val locationClass = eqObj.javaClass
                    try {
                        val methodMethod = locationClass.getMethod("method")
                        val method = methodMethod.invoke(eqObj)
                        if (method != null) {
                            val declaringType = method.javaClass.getMethod("declaringType")
                            val type = declaringType.invoke(method)
                            if (type != null) {
                                val nameMethod = type.javaClass.getMethod("name")
                                return nameMethod.invoke(type) as? String
                            }
                        }
                    } catch (e: Exception) { }
                }
            } catch (e: Exception) { }

        } catch (e: Exception) {
            log.debug("extractClassName failed: ${e.message}")
        }
        return null
    }
}
