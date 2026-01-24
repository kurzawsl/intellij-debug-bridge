package com.morpheus.debugbridge.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Application-level service that acts as a central registry for all projects and debug sessions.
 * This is the single source of truth for the REST API when dealing with multi-project scenarios.
 *
 * Thread Safety: Uses ReentrantReadWriteLock for proper atomicity:
 * - Read operations (get*, find*, is*, has*) take readLock - concurrent reads allowed
 * - Write operations (register*, unregister*, touch*) take writeLock - exclusive access
 *
 * This ensures compound operations across multiple maps are fully atomic.
 */
@Service(Service.Level.APP)
class AppRegistryService {

    private val log = Logger.getInstance(AppRegistryService::class.java)

    // ReadWriteLock for proper atomicity of compound operations
    // Read methods: multiple threads can read concurrently
    // Write methods: exclusive access ensures atomicity across all maps
    private val lock = ReentrantReadWriteLock()

    // Primary data: projectId -> ProjectInfo
    private val projects = HashMap<String, ProjectInfo>()

    // Primary data: sessionId -> SessionReference
    private val sessions = HashMap<String, SessionReference>()

    // Index: projectId -> set of sessionIds (for O(1) project session lookup)
    private val sessionsByProject = HashMap<String, MutableSet<String>>()

    // Index: XDebugSession -> sessionId (for O(1) reverse lookup)
    private val sessionIdByDebugSession = HashMap<XDebugSession, String>()

    // Last touched session state - AtomicReference for lock-free updates in touchSession
    // This avoids the need for writeLock in touchSession, reducing contention
    private data class LastTouchedInfo(val sessionId: String, val timestamp: Instant)
    private val lastTouched = AtomicReference<LastTouchedInfo?>(null)

    /**
     * Information about a registered project
     */
    data class ProjectInfo(
        val projectId: String,
        val project: Project,
        val name: String,
        val basePath: String,
        val registeredAt: Instant = Instant.now()
    )

    /**
     * Reference to a debug session with its project context
     */
    data class SessionReference(
        val sessionId: String,
        val projectId: String,
        val session: XDebugSession,
        val sessionName: String,
        val createdAt: Instant = Instant.now()
    )

    // ==================== Project Registration (Write Operations) ====================

    /**
     * Register a project when it opens or when plugin initializes.
     *
     * @param project The project to register
     * @return The stable project ID
     * @throws IllegalArgumentException if project is the default project or has no valid path
     */
    fun registerProject(project: Project): String {
        require(!project.isDefault) {
            "Cannot register the default project. Only real projects should be registered."
        }

        val projectId = generateProjectId(project)
        val basePath = requireNotNull(project.basePath) {
            "Cannot register project '${project.name}' without a basePath"
        }

        val info = ProjectInfo(
            projectId = projectId,
            project = project,
            name = project.name,
            basePath = basePath
        )

        lock.write {
            projects[projectId] = info
        }

        log.info("Project registered: $projectId (${project.name}) at $basePath")
        return projectId
    }

    /**
     * Safely register a project, returning null for default projects or projects without paths.
     * Use this when you want to handle edge cases gracefully.
     */
    fun registerProjectOrNull(project: Project): String? {
        return try {
            registerProject(project)
        } catch (e: IllegalArgumentException) {
            log.debug("Skipping project registration: ${e.message}")
            null
        }
    }

    /**
     * Unregister a project when it closes
     */
    fun unregisterProject(project: Project) {
        try {
            val projectId = generateProjectId(project)
            unregisterProjectById(projectId)
        } catch (e: IllegalStateException) {
            log.warn("Failed to unregister project: ${e.message}")
        }
    }

    /**
     * Unregister a project by its ID.
     * Atomically removes project and all associated sessions.
     */
    fun unregisterProjectById(projectId: String) {
        var removedSessionCount = 0
        var projectName = "unknown"

        lock.write {
            val projectInfo = projects.remove(projectId)
            if (projectInfo == null) {
                log.debug("Project not found for unregistration: $projectId")
                return
            }
            projectName = projectInfo.name

            // Get and remove all session IDs for this project from index
            val sessionIds = sessionsByProject.remove(projectId) ?: emptySet()
            removedSessionCount = sessionIds.size

            // Remove each session atomically (we already have the write lock)
            sessionIds.forEach { sessionId ->
                val ref = sessions.remove(sessionId)
                if (ref != null) {
                    sessionIdByDebugSession.remove(ref.session)
                    // Clear lastTouched if this was it (atomic update)
                    lastTouched.updateAndGet { current ->
                        if (current?.sessionId == sessionId) null else current
                    }
                }
            }
        }

        log.info("Project unregistered: $projectId ($projectName), removed $removedSessionCount sessions")
    }

    // ==================== Session Registration (Write Operations) ====================

    /**
     * Register a new debug session.
     * Atomically updates all maps and marks session as last touched.
     */
    fun registerSession(sessionId: String, projectId: String, session: XDebugSession): SessionReference {
        val reference = SessionReference(
            sessionId = sessionId,
            projectId = projectId,
            session = session,
            sessionName = session.sessionName
        )

        var projectName = "unknown"

        lock.write {
            // Add to primary storage
            sessions[sessionId] = reference

            // Update indexes
            val sessionSet = sessionsByProject.getOrPut(projectId) { mutableSetOf() }
            sessionSet.add(sessionId)
            sessionIdByDebugSession[session] = sessionId

            // Mark as last touched (atomic update)
            lastTouched.set(LastTouchedInfo(sessionId, Instant.now()))

            projectName = projects[projectId]?.name ?: "unknown"
        }

        log.info("Session registered: $sessionId (${session.sessionName}) in project $projectId ($projectName)")
        return reference
    }

    /**
     * Unregister a debug session when it stops.
     * Atomically removes from all maps.
     */
    fun unregisterSession(sessionId: String) {
        lock.write {
            val ref = sessions.remove(sessionId) ?: return@write

            // Clean up indexes
            sessionsByProject[ref.projectId]?.let { set ->
                set.remove(sessionId)
                if (set.isEmpty()) {
                    sessionsByProject.remove(ref.projectId)
                }
            }
            sessionIdByDebugSession.remove(ref.session)

            // Clear lastTouched if this was it (atomic update)
            lastTouched.updateAndGet { current ->
                if (current?.sessionId == sessionId) null else current
            }
        }

        log.info("Session unregistered: $sessionId")
    }

    /**
     * Mark a session as "touched" (used for backward compatibility with old API).
     * Uses writeLock to ensure atomicity of existence check and update.
     *
     * Note: We use writeLock (not readLock + AtomicReference) because:
     * - Check and update MUST be in the same critical section to prevent TOCTOU
     * - A session could be unregistered between check and update otherwise
     * - The contention cost is acceptable since touchSession is called infrequently
     *   (once per user-initiated debug action, not on hot paths)
     */
    fun touchSession(sessionId: String) {
        lock.write {
            // Check existence and update atomically under writeLock
            if (!sessions.containsKey(sessionId)) return@write
            lastTouched.set(LastTouchedInfo(sessionId, Instant.now()))
        }
    }

    // ==================== Read Operations (Concurrent Reads Allowed) ====================

    /**
     * Get all registered projects (snapshot)
     */
    fun getAllProjects(): List<ProjectInfo> = lock.read {
        projects.values.toList()
    }

    /**
     * Get a project by its ID
     */
    fun getProject(projectId: String): ProjectInfo? = lock.read {
        projects[projectId]
    }

    /**
     * Get a project by its Project object
     */
    fun getProjectByObject(project: Project): ProjectInfo? {
        return try {
            val projectId = generateProjectId(project)
            lock.read { projects[projectId] }
        } catch (e: IllegalStateException) {
            null
        }
    }

    /**
     * Find project ID for a given Project object
     */
    fun getProjectId(project: Project): String = generateProjectId(project)

    /**
     * Get all active debug sessions (snapshot)
     */
    fun getAllSessions(): List<SessionReference> = lock.read {
        sessions.values.toList()
    }

    /**
     * Get sessions for a specific project.
     */
    fun getSessionsForProject(projectId: String): List<SessionReference> = lock.read {
        val sessionIds = sessionsByProject[projectId] ?: return@read emptyList()
        sessionIds.mapNotNull { sessions[it] }
    }

    /**
     * Get a session by its ID
     */
    fun getSession(sessionId: String): SessionReference? = lock.read {
        sessions[sessionId]
    }

    /**
     * Get the last touched session (for backward compatibility).
     */
    fun getLastTouchedSession(): SessionReference? = lock.read {
        lastTouched.get()?.sessionId?.let { sessions[it] }
    }

    /**
     * Get the XDebugSession for a session ID
     */
    fun getDebugSession(sessionId: String): XDebugSession? = lock.read {
        sessions[sessionId]?.session
    }

    /**
     * Find session ID by XDebugSession object.
     */
    fun findSessionId(session: XDebugSession): String? = lock.read {
        val sessionId = sessionIdByDebugSession[session] ?: return@read null
        // Verify session still exists
        if (sessions.containsKey(sessionId)) sessionId else null
    }

    // ==================== Utility Methods ====================

    /**
     * Check if a project is registered
     */
    fun isProjectRegistered(project: Project): Boolean {
        return try {
            val projectId = generateProjectId(project)
            lock.read { projects.containsKey(projectId) }
        } catch (e: IllegalStateException) {
            false
        }
    }

    /**
     * Check if there are any active sessions
     */
    fun hasActiveSessions(): Boolean = lock.read { sessions.isNotEmpty() }

    /**
     * Get count of active sessions
     */
    fun getSessionCount(): Int = lock.read { sessions.size }

    /**
     * Get count of registered projects
     */
    fun getProjectCount(): Int = lock.read { projects.size }

    companion object {
        fun getInstance(): AppRegistryService {
            return ApplicationManager.getApplication().getService(AppRegistryService::class.java)
        }

        /**
         * Generate a stable, deterministic project ID.
         * Uses project.locationHash when available (preferred - stable across IDE restarts),
         * falls back to SHA-256 of base path.
         *
         * Note: If a user moves the project directory, the ID will change.
         * For truly persistent IDs, consider storing a UUID in the project's .idea directory.
         *
         * @throws IllegalStateException if project has no locationHash and no basePath
         */
        fun generateProjectId(project: Project): String {
            // Prefer locationHash as it's more stable and unique
            val locationHash = project.locationHash
            if (locationHash.isNotEmpty()) {
                return locationHash
            }

            // Fallback to SHA-256 of base path
            val basePath = project.basePath
            if (basePath != null) {
                return generateIdFromPath(basePath)
            }

            // NEVER use project name - names are not unique!
            throw IllegalStateException(
                "Cannot generate project ID: project '${project.name}' has no locationHash and no basePath. " +
                "This typically indicates an invalid or virtual project that should not be registered."
            )
        }

        /**
         * Generate ID from a path string using SHA-256.
         * Returns full hash (64 hex characters) to minimize collision risk.
         */
        fun generateIdFromPath(path: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(path.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
