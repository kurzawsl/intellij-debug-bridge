package com.morpheus.debugbridge

import com.intellij.openapi.diagnostic.Logger
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManagerListener
import com.morpheus.debugbridge.service.DebugStateService

/**
 * Listens for debug session lifecycle events (start, pause, resume, stop).
 * This is registered as a project listener in plugin.xml.
 */
class DebugSessionListener : XDebuggerManagerListener {

    private val log = Logger.getInstance(DebugSessionListener::class.java)

    /**
     * Called when a new debug session is created and started
     */
    override fun processStarted(debugProcess: XDebugProcess) {
        val session = debugProcess.session
        log.info("Debug process started: ${session.sessionName}")

        // Notify the state service
        DebugStateService.getInstance().onSessionStarted(session)

        // Add a listener to this specific session for pause/resume events
        session.addSessionListener(SessionEventListener(session))
    }

    /**
     * Called when a debug session is stopped
     */
    override fun processStopped(debugProcess: XDebugProcess) {
        val session = debugProcess.session
        log.info("Debug process stopped: ${session.sessionName}")

        DebugStateService.getInstance().onSessionStopped(session)
    }

    /**
     * Inner listener for individual session events (pause, resume, etc.)
     */
    private inner class SessionEventListener(
        private val session: XDebugSession
    ) : XDebugSessionListener {

        override fun sessionPaused() {
            log.info("Session paused: ${session.sessionName}")
            DebugStateService.getInstance().onSessionPaused(session)
        }

        override fun sessionResumed() {
            log.info("Session resumed: ${session.sessionName}")
            DebugStateService.getInstance().onSessionResumed(session)
        }

        override fun sessionStopped() {
            log.info("Session stopped (from listener): ${session.sessionName}")
            // Already handled by processStopped, but good to have
        }

        override fun stackFrameChanged() {
            log.debug("Stack frame changed in: ${session.sessionName}")
            // Update state when user selects different frame
            if (session.isSuspended) {
                DebugStateService.getInstance().onSessionPaused(session)
            }
        }

        override fun beforeSessionResume() {
            log.debug("About to resume: ${session.sessionName}")
        }
    }
}
