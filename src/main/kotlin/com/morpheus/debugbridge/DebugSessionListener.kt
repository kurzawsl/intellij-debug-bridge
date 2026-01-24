package com.morpheus.debugbridge

import com.intellij.openapi.diagnostic.Logger
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebuggerManagerListener
import com.morpheus.debugbridge.service.ConsoleLogService
import com.morpheus.debugbridge.service.DebugStateService
import com.morpheus.debugbridge.service.ProjectStateService

/**
 * Listens for debug session lifecycle events (start, stop).
 * This is registered as a project listener in plugin.xml.
 *
 * Responsibilities:
 * - Notify ProjectStateService when sessions start/stop (multi-project support)
 * - Notify DebugStateService for backward compatibility
 * - Attach console logging to new sessions
 *
 * Note: Pause/resume/stackFrame events are handled by SessionEventListener
 * which is added to each session by ProjectStateService.registerSession().
 */
class DebugSessionListener : XDebuggerManagerListener {

    private val log = Logger.getInstance(DebugSessionListener::class.java)

    /**
     * Called when a new debug session is created and started
     */
    override fun processStarted(debugProcess: XDebugProcess) {
        val session = debugProcess.session
        val project = session.project

        log.info("Debug process started: ${session.sessionName} in project ${project.name}")

        // Clear previous console output and attach to new session
        ConsoleLogService.clear()
        ConsoleLogService.attachToSession(session)

        // Register with ProjectStateService (handles multi-project tracking)
        // This also adds session listeners for pause/resume events
        try {
            val projectStateService = ProjectStateService.getInstance(project)
            projectStateService.registerSession(session)
        } catch (e: Exception) {
            log.warn("Failed to register session with ProjectStateService: ${e.message}")
        }

        // Notify legacy DebugStateService for backward compatibility
        DebugStateService.getInstance().onSessionStarted(session)
    }

    /**
     * Called when a debug session is stopped
     */
    override fun processStopped(debugProcess: XDebugProcess) {
        val session = debugProcess.session
        val project = session.project

        log.info("Debug process stopped: ${session.sessionName} in project ${project.name}")

        // Unregister from ProjectStateService
        try {
            val projectStateService = ProjectStateService.getInstance(project)
            projectStateService.unregisterSession(session)
        } catch (e: Exception) {
            log.warn("Failed to unregister session from ProjectStateService: ${e.message}")
        }

        // Notify legacy DebugStateService
        DebugStateService.getInstance().onSessionStopped(session)
    }
}
