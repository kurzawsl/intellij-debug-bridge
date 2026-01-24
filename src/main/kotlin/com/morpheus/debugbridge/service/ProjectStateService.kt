package com.morpheus.debugbridge.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import java.util.UUID

/**
 * Project-level service that provides a project-scoped interface to debug sessions.
 * One instance exists per open project in IntelliJ.
 *
 * IMPORTANT: This service does NOT maintain its own cache of sessions.
 * All session state is stored in the central AppRegistryService (Single Source of Truth).
 * This eliminates state synchronization bugs and memory leaks.
 *
 * Responsibilities:
 * - Register/unregister this project with AppRegistryService
 * - Generate session IDs and delegate registration to AppRegistryService
 * - Attach session listeners for pause/resume events
 */
@Service(Service.Level.PROJECT)
class ProjectStateService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(ProjectStateService::class.java)

    // Unique stable ID for this project (lazy to avoid calling generateProjectId on default projects)
    val projectId: String by lazy {
        if (project.isDefault) {
            throw IllegalStateException("Cannot get projectId for default project")
        }
        AppRegistryService.generateProjectId(project)
    }

    init {
        if (!project.isDefault) {
            // Register this project with the central registry
            AppRegistryService.getInstance().registerProject(project)
            log.info("ProjectStateService initialized for: ${project.name} (ID: $projectId)")
        }
    }

    /**
     * Get all sessions in this project.
     * Delegates to AppRegistryService (single source of truth).
     */
    fun getSessions(): List<AppRegistryService.SessionReference> {
        return AppRegistryService.getInstance().getSessionsForProject(projectId)
    }

    /**
     * Get a specific session by ID.
     * Delegates to AppRegistryService.
     */
    fun getSession(sessionId: String): AppRegistryService.SessionReference? {
        val session = AppRegistryService.getInstance().getSession(sessionId)
        // Only return if it belongs to this project
        return if (session?.projectId == projectId) session else null
    }

    /**
     * Get session count for this project
     */
    fun getSessionCount(): Int = getSessions().size

    /**
     * Check if this project has any active debug sessions
     */
    fun hasActiveSessions(): Boolean = getSessions().isNotEmpty()

    /**
     * Get the Project object
     */
    fun getProject(): Project = project

    /**
     * Register a session (called by DebugSessionListener).
     * Generates a session ID and delegates to AppRegistryService.
     */
    internal fun registerSession(session: XDebugSession): String {
        val sessionId = UUID.randomUUID().toString()

        // Register with central registry (single source of truth)
        AppRegistryService.getInstance().registerSession(sessionId, projectId, session)

        // Add session-specific listener for pause/resume events
        session.addSessionListener(SessionEventListener(sessionId, session))

        log.info("Session registered in project ${project.name}: $sessionId (${session.sessionName})")
        return sessionId
    }

    /**
     * Unregister a session (called by DebugSessionListener).
     * Delegates to AppRegistryService using its reverse index for O(1) lookup.
     */
    internal fun unregisterSession(session: XDebugSession) {
        // Use AppRegistryService's reverse index for O(1) lookup
        val sessionId = AppRegistryService.getInstance().findSessionId(session)
        if (sessionId != null) {
            AppRegistryService.getInstance().unregisterSession(sessionId)
            log.info("Session unregistered from project ${project.name}: $sessionId")
        }
    }

    /**
     * Find session ID by XDebugSession.
     * Delegates to AppRegistryService.
     */
    fun findSessionId(session: XDebugSession): String? {
        return AppRegistryService.getInstance().findSessionId(session)
    }

    /**
     * Listener for individual session events (pause, resume, etc.)
     * Added to each session when it's registered.
     */
    private inner class SessionEventListener(
        private val sessionId: String,
        private val session: XDebugSession
    ) : XDebugSessionListener {

        override fun sessionPaused() {
            log.info("Session paused: $sessionId (${session.sessionName})")
            // Touch the session in registry to mark it as most recently active
            AppRegistryService.getInstance().touchSession(sessionId)
            // Notify DebugStateService for backward compatibility
            DebugStateService.getInstance().onSessionPaused(session)
        }

        override fun sessionResumed() {
            log.info("Session resumed: $sessionId (${session.sessionName})")
            AppRegistryService.getInstance().touchSession(sessionId)
            DebugStateService.getInstance().onSessionResumed(session)
        }

        override fun sessionStopped() {
            log.info("Session stopped (from listener): $sessionId (${session.sessionName})")
            // Main cleanup is handled by processStopped in DebugSessionListener
        }

        override fun stackFrameChanged() {
            // Only update if suspended - this is called during stepping
            if (session.isSuspended) {
                AppRegistryService.getInstance().touchSession(sessionId)
                DebugStateService.getInstance().onSessionPaused(session)
            }
        }

        override fun beforeSessionResume() {
            log.debug("About to resume: $sessionId")
        }
    }

    /**
     * Called when the project is being closed.
     * Only unregisters the project - AppRegistryService.unregisterProjectById handles session cleanup.
     */
    override fun dispose() {
        log.info("Disposing ProjectStateService for: ${project.name}")

        // Just unregister the project - AppRegistryService handles session cleanup atomically
        // This avoids the double-work of manually unregistering each session
        AppRegistryService.getInstance().unregisterProjectById(projectId)
    }

    companion object {
        fun getInstance(project: Project): ProjectStateService {
            return project.getService(ProjectStateService::class.java)
        }

        /**
         * Get ProjectStateService for a project ID (looks up via registry)
         */
        fun getInstanceById(projectId: String): ProjectStateService? {
            val projectInfo = AppRegistryService.getInstance().getProject(projectId)
            return projectInfo?.project?.let { getInstance(it) }
        }
    }
}
