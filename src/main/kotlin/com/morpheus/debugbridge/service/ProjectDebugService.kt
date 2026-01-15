package com.morpheus.debugbridge.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.morpheus.debugbridge.model.BreakpointInfo

/**
 * Project-level service for debug operations within a specific project.
 * One instance per open project.
 */
@Service(Service.Level.PROJECT)
class ProjectDebugService(private val project: Project) {

    private val log = Logger.getInstance(ProjectDebugService::class.java)

    companion object {
        fun getInstance(project: Project): ProjectDebugService {
            return project.getService(ProjectDebugService::class.java)
        }
    }

    /**
     * Get the XDebuggerManager for this project
     */
    fun getDebuggerManager(): XDebuggerManager {
        return XDebuggerManager.getInstance(project)
    }

    /**
     * Get current debug session (if any)
     */
    fun getCurrentSession(): XDebugSession? {
        return getDebuggerManager().currentSession
    }

    /**
     * Get all breakpoints in this project
     */
    fun getBreakpoints(): List<BreakpointInfo> {
        val breakpointManager = getDebuggerManager().breakpointManager
        val allBreakpoints = breakpointManager.allBreakpoints

        return allBreakpoints.mapNotNull { bp ->
            if (bp is XLineBreakpoint<*>) {
                val file = bp.sourcePosition?.file
                file?.let {
                    BreakpointInfo(
                        file = it.name,
                        line = bp.line + 1, // Convert to 1-based
                        enabled = bp.isEnabled,
                        condition = bp.conditionExpression?.expression
                    )
                }
            } else null
        }
    }

    /**
     * Step over in current session
     */
    fun stepOver(): Boolean {
        val session = getCurrentSession() ?: return false
        if (!session.isSuspended) return false

        session.stepOver(false)
        return true
    }

    /**
     * Step into in current session
     */
    fun stepInto(): Boolean {
        val session = getCurrentSession() ?: return false
        if (!session.isSuspended) return false

        session.stepInto()
        return true
    }

    /**
     * Step out of current method
     */
    fun stepOut(): Boolean {
        val session = getCurrentSession() ?: return false
        if (!session.isSuspended) return false

        session.stepOut()
        return true
    }

    /**
     * Resume execution
     */
    fun resume(): Boolean {
        val session = getCurrentSession() ?: return false
        if (!session.isSuspended) return false

        session.resume()
        return true
    }

    /**
     * Pause execution (if running)
     */
    fun pause(): Boolean {
        val session = getCurrentSession() ?: return false
        if (session.isSuspended) return false

        session.pause()
        return true
    }

    /**
     * Stop debug session
     */
    fun stop(): Boolean {
        val session = getCurrentSession() ?: return false
        session.stop()
        return true
    }
}
