package com.morpheus.debugbridge.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.morpheus.debugbridge.config.TimeoutConfig
import com.morpheus.debugbridge.model.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Application-level service that tracks debug state across all projects.
 * This is a singleton - one instance for the entire IDE.
 *
 * PERFORMANCE: State extraction is now LAZY - stack traces are only
 * computed when explicitly requested via getStateWithStackTrace().
 */
@Service(Service.Level.APP)
class DebugStateService {

    private val log = Logger.getInstance(DebugStateService::class.java)

    // Track sessions by project name
    private val sessions = ConcurrentHashMap<String, XDebugSession>()

    // FIX #3: Lock for session management to avoid race between sessions and activeSession
    private val sessionLock = ReentrantLock()

    // FIX: Include activeSession in state holder to prevent race condition where
    // activeSession and cachedState become inconsistent
    private data class CachedStateHolder(
        val state: DebugState,
        val stackTrace: List<StackFrameInfo>?,
        val version: Long,
        val activeSession: XDebugSession?  // Moved into holder for atomicity
    )

    private val cachedStateHolder = AtomicReference(
        CachedStateHolder(
            state = DebugState(
                status = DebugStatus.NO_SESSION,
                sessionName = null,
                location = null
            ),
            stackTrace = null,
            version = 0L,
            activeSession = null
        )
    )

    // Version counter for cache invalidation
    private val stateVersion = AtomicLong(0)

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
        sessionLock.withLock {
            sessions[name] = session
        }
        updateStateLightweight(DebugStatus.RUNNING, session)
    }

    /**
     * Called when a debug session is paused (breakpoint hit, step completed)
     * PERFORMANCE: Only extracts location, NOT full stack trace
     */
    fun onSessionPaused(session: XDebugSession) {
        val name = session.sessionName
        log.info("Debug session paused: $name")
        updateStateLightweight(DebugStatus.SUSPENDED, session)
    }

    /**
     * Called when a debug session resumes
     */
    fun onSessionResumed(session: XDebugSession) {
        val name = session.sessionName
        log.info("Debug session resumed: $name")
        updateStateLightweight(DebugStatus.RUNNING, session)
    }

    /**
     * Called when a debug session stops
     */
    fun onSessionStopped(session: XDebugSession) {
        val name = session.sessionName
        log.info("Debug session stopped: $name")

        // Determine new active session atomically
        val newActiveSession: XDebugSession?
        val isEmpty: Boolean
        sessionLock.withLock {
            sessions.remove(name)
            val currentHolder = cachedStateHolder.get()
            newActiveSession = if (currentHolder.activeSession == session) {
                sessions.values.firstOrNull()
            } else {
                currentHolder.activeSession
            }
            isEmpty = sessions.isEmpty()
        }

        if (isEmpty) {
            updateStateToNoSession()
        } else {
            newActiveSession?.let { updateStateLightweight(DebugStatus.RUNNING, it) }
        }
    }

    /**
     * Get lightweight state (fast - no stack trace computation)
     * FIX #2: Read from atomic holder to ensure consistent state
     */
    fun getState(): DebugState {
        val holder = cachedStateHolder.get()
        val currentVersion = stateVersion.get()
        // Return cached state with lazily computed stack trace if available
        if (holder.version == currentVersion && holder.stackTrace != null) {
            return holder.state.copy(stackTrace = holder.stackTrace)
        }
        return holder.state
    }

    /**
     * Get full state with stack trace (slower - computes stack trace on demand)
     * Use this only when stack trace is actually needed.
     * FIX: Read session from holder to avoid race with state
     */
    fun getStateWithStackTrace(): DebugState {
        // Read holder ONCE for consistency
        val holder = cachedStateHolder.get()
        val session = holder.activeSession ?: return holder.state
        val currentVersion = holder.version

        // Check if cache is valid
        if (holder.stackTrace != null) {
            return holder.state.copy(stackTrace = holder.stackTrace)
        }

        // Compute stack trace lazily
        val stackTrace = extractStackTraceFast(session)

        // Atomically update the holder with new stack trace (CAS loop)
        while (true) {
            val current = cachedStateHolder.get()
            // Only update if version still matches (state hasn't changed)
            if (current.version != currentVersion) {
                // State changed, return computed trace with current state
                return current.state.copy(stackTrace = stackTrace)
            }
            val updated = current.copy(stackTrace = stackTrace)
            if (cachedStateHolder.compareAndSet(current, updated)) {
                break
            }
        }

        return holder.state.copy(stackTrace = stackTrace)
    }

    /**
     * Get the active debug session (if any)
     * FIX: Read from holder for consistency
     */
    fun getActiveSession(): XDebugSession? = cachedStateHolder.get().activeSession

    /**
     * Check if we have an active suspended session
     */
    fun isSuspended(): Boolean = cachedStateHolder.get().state.status == DebugStatus.SUSPENDED

    // ==================== Multi-Session Support (V2 API) ====================

    /**
     * Get debug session by session ID (from AppRegistryService)
     */
    fun getSessionById(sessionId: String): XDebugSession? {
        return AppRegistryService.getInstance().getDebugSession(sessionId)
    }

    /**
     * Get state for a specific session by ID
     */
    fun getStateForSession(sessionId: String): DebugState? {
        val session = getSessionById(sessionId) ?: return null
        return extractStateFromSession(session)
    }

    /**
     * Get state with stack trace for a specific session
     */
    fun getStateWithStackTraceForSession(sessionId: String): DebugState? {
        val session = getSessionById(sessionId) ?: return null
        val state = extractStateFromSession(session)
        val stackTrace = extractStackTraceFast(session)
        return state.copy(stackTrace = stackTrace)
    }

    /**
     * Extract state from any session (not just active one)
     */
    private fun extractStateFromSession(session: XDebugSession): DebugState {
        val status = when {
            !session.isStopped && session.isSuspended -> DebugStatus.SUSPENDED
            !session.isStopped -> DebugStatus.RUNNING
            else -> DebugStatus.TERMINATED
        }

        return DebugState(
            status = status,
            sessionName = session.sessionName,
            location = extractLocationFast(session),
            stackTrace = emptyList() // Lazy - computed on demand
        )
    }

    /**
     * Update state to NO_SESSION atomically
     */
    private fun updateStateToNoSession() {
        val newVersion = stateVersion.incrementAndGet()
        val newHolder = CachedStateHolder(
            state = DebugState(
                status = DebugStatus.NO_SESSION,
                sessionName = null,
                location = null
            ),
            stackTrace = null,
            version = newVersion,
            activeSession = null  // FIX: Include in holder for atomicity
        )
        cachedStateHolder.set(newHolder)
    }

    /**
     * FAST state update - only extracts location, not stack trace
     * FIX: Atomically update session with state in single holder
     */
    private fun updateStateLightweight(status: DebugStatus, session: XDebugSession) {
        val newVersion = stateVersion.incrementAndGet()
        val location = extractLocationFast(session)

        val newHolder = CachedStateHolder(
            state = DebugState(
                status = status,
                sessionName = session.sessionName,
                location = location,
                stackTrace = emptyList() // Lazy - computed on demand
            ),
            stackTrace = null, // Invalidate cache
            version = newVersion,
            activeSession = session  // FIX: Include in holder for atomicity
        )
        cachedStateHolder.set(newHolder)
    }

    /**
     * FAST location extraction - no blocking, no async calls
     */
    private fun extractLocationFast(session: XDebugSession): DebugLocation? {
        val frame = session.currentStackFrame ?: return null
        val sourcePosition = frame.sourcePosition ?: return null
        val file = sourcePosition.file

        return DebugLocation(
            file = file.name,
            filePath = file.path,
            line = sourcePosition.line + 1,
            method = extractMethodNameFast(frame),
            className = extractClassNameFast(frame)
        )
    }

    /**
     * Stack trace extraction with shorter timeout and better error handling
     */
    private fun extractStackTraceFast(session: XDebugSession): List<StackFrameInfo> {
        val suspendContext = session.suspendContext ?: return emptyList()
        val executionStack = suspendContext.activeExecutionStack ?: return emptyList()

        val frames = java.util.concurrent.CopyOnWriteArrayList<StackFrameInfo>()
        val latch = java.util.concurrent.CountDownLatch(1)

        executionStack.computeStackFrames(0, object : com.intellij.xdebugger.frame.XExecutionStack.XStackFrameContainer {
            override fun addStackFrames(
                frameList: MutableList<out com.intellij.xdebugger.frame.XStackFrame>,
                last: Boolean
            ) {
                for ((index, frame) in frameList.withIndex()) {
                    frame.sourcePosition?.let { pos ->
                        frames.add(
                            StackFrameInfo(
                                index = index,
                                method = extractMethodNameFast(frame) ?: "unknown",
                                file = pos.file.name,
                                line = pos.line + 1,
                                className = extractClassNameFast(frame)
                            )
                        )
                    }
                }
                if (last) latch.countDown()
            }

            override fun errorOccurred(errorMessage: String) {
                log.warn("Error computing stack frames: $errorMessage")
                latch.countDown()
            }
        })

        // Stack trace timeout - configurable via TimeoutConfig
        if (!latch.await(TimeoutConfig.STACK_TRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            log.debug("Stack frames async - using current frame only")
            // Return current frame immediately, don't wait
            session.currentStackFrame?.let { frame ->
                frame.sourcePosition?.let { pos ->
                    if (frames.isEmpty()) {
                        frames.add(
                            StackFrameInfo(
                                index = 0,
                                method = extractMethodNameFast(frame) ?: "unknown",
                                file = pos.file.name,
                                line = pos.line + 1,
                                className = extractClassNameFast(frame)
                            )
                        )
                    }
                }
            }
        }

        return frames.toList()
    }

    /**
     * Fast method name extraction with caching
     */
    private fun extractMethodNameFast(frame: com.intellij.xdebugger.frame.XStackFrame): String? {
        return try {
            extractMethodNameReflection(frame)
        } catch (e: Exception) {
            log.debug("extractMethodNameFast failed: ${e.message}")
            null
        }
    }

    /**
     * Fast class name extraction with caching
     */
    private fun extractClassNameFast(frame: com.intellij.xdebugger.frame.XStackFrame): String? {
        return try {
            extractClassNameReflection(frame)
        } catch (e: Exception) {
            log.debug("extractClassNameFast failed: ${e.message}")
            null
        }
    }

    private fun extractMethodNameReflection(frame: com.intellij.xdebugger.frame.XStackFrame): String? {
        val frameClass = frame.javaClass

        // Try getDescriptor -> getMethod -> name (works for Java debugger frames)
        try {
            val getDescriptor = frameClass.getMethod("getDescriptor")
            val descriptor = getDescriptor.invoke(frame) ?: return null

            try {
                val getMethod = descriptor.javaClass.getMethod("getMethod")
                val method = getMethod.invoke(descriptor) ?: return null
                val nameMethod = method.javaClass.getMethod("name")
                return nameMethod.invoke(method) as? String
            } catch (e: Exception) {
                // Expected: getMethod may not exist, try getName fallback
                log.trace("getMethod not available, trying getName: ${e.message}")
            }

            try {
                val getName = descriptor.javaClass.getMethod("getName")
                return getName.invoke(descriptor) as? String
            } catch (e: Exception) {
                // Expected: getName may not exist on this descriptor type
                log.trace("getName not available: ${e.message}")
            }
        } catch (e: Exception) {
            // Expected: getDescriptor may not exist, try equalityObject approach
            log.trace("getDescriptor approach failed: ${e.message}")
        }

        // Try equalityObject -> Location -> method -> name (alternative approach)
        try {
            val getEqualityObject = frameClass.getMethod("getEqualityObject")
            val eqObj = getEqualityObject.invoke(frame) ?: return null
            val methodMethod = eqObj.javaClass.getMethod("method")
            val method = methodMethod.invoke(eqObj) ?: return null
            val nameMethod = method.javaClass.getMethod("name")
            return nameMethod.invoke(method) as? String
        } catch (e: Exception) {
            // Expected: equalityObject approach may not work for all frame types
            log.trace("equalityObject approach failed: ${e.message}")
        }

        return null
    }

    private fun extractClassNameReflection(frame: com.intellij.xdebugger.frame.XStackFrame): String? {
        val frameClass = frame.javaClass

        // Try getDescriptor -> getMethod -> declaringType -> name (works for Java debugger frames)
        try {
            val getDescriptor = frameClass.getMethod("getDescriptor")
            val descriptor = getDescriptor.invoke(frame) ?: return null

            try {
                val getMethod = descriptor.javaClass.getMethod("getMethod")
                val method = getMethod.invoke(descriptor) ?: return null
                val declaringType = method.javaClass.getMethod("declaringType")
                val type = declaringType.invoke(method) ?: return null
                val nameMethod = type.javaClass.getMethod("name")
                return nameMethod.invoke(type) as? String
            } catch (e: Exception) {
                // Expected: this reflection chain may not work for all descriptor types
                log.trace("getMethod/declaringType chain failed: ${e.message}")
            }
        } catch (e: Exception) {
            // Expected: getDescriptor may not exist, try equalityObject approach
            log.trace("getDescriptor approach failed for class name: ${e.message}")
        }

        // Try equalityObject -> method -> declaringType -> name (alternative approach)
        try {
            val getEqualityObject = frameClass.getMethod("getEqualityObject")
            val eqObj = getEqualityObject.invoke(frame) ?: return null
            val methodMethod = eqObj.javaClass.getMethod("method")
            val method = methodMethod.invoke(eqObj) ?: return null
            val declaringType = method.javaClass.getMethod("declaringType")
            val type = declaringType.invoke(method) ?: return null
            val nameMethod = type.javaClass.getMethod("name")
            return nameMethod.invoke(type) as? String
        } catch (e: Exception) {
            // Expected: equalityObject approach may not work for all frame types
            log.trace("equalityObject approach failed for class name: ${e.message}")
        }

        return null
    }

}
