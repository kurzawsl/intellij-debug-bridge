package com.morpheus.debugbridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.morpheus.debugbridge.api.RestServer
import com.morpheus.debugbridge.service.AppRegistryService

/**
 * Plugin startup activity - runs when IDE starts and a project is opened.
 * Starts the REST server that exposes debug state to external tools.
 */
class DebugBridgeStartup : ProjectActivity {

    private val log = Logger.getInstance(DebugBridgeStartup::class.java)

    companion object {
        // Single REST server for entire IDE (not per-project)
        @Volatile
        private var restServer: RestServer? = null
        private val serverLock = Any()

        fun getServer(): RestServer? = restServer
    }

    override suspend fun execute(project: Project) {
        log.info("Debug Bridge startup for project: ${project.name}")

        // Register project with AppRegistryService for V2 API support
        val registry = AppRegistryService.getInstance()
        val projectId = registry.registerProjectOrNull(project)
        if (projectId != null) {
            log.info("Project registered with V2 API: $projectId (${project.name})")

            // Unregister when project closes
            Disposer.register(project, Disposable {
                log.info("Project closing, unregistering: ${project.name}")
                registry.unregisterProject(project)
            })
        }

        // Start REST server only once (first project to open)
        synchronized(serverLock) {
            if (restServer == null) {
                log.info("Starting Debug Bridge REST server...")
                // FIX: Fully initialize server before publishing reference to avoid
                // race where another thread gets an unstarted server
                val server = RestServer(port = 19999)
                server.start()
                restServer = server

                // Register shutdown hook
                Disposer.register(ApplicationManager.getApplication(), Disposable {
                    log.info("Shutting down Debug Bridge REST server...")
                    restServer?.stop()
                    restServer = null
                })

                log.info("""

                    ╔══════════════════════════════════════════════════════════════╗
                    ║           Debug Bridge for AI - ACTIVE                       ║
                    ║                                                              ║
                    ║   REST API: http://localhost:19999                           ║
                    ║                                                              ║
                    ║   Endpoints:                                                 ║
                    ║     GET  /debug/state       - Current debug state            ║
                    ║     GET  /debug/variables   - Variables at current location  ║
                    ║     POST /debug/step-over   - Step over (F8)                 ║
                    ║     POST /debug/step-into   - Step into (F7)                 ║
                    ║     POST /debug/step-out    - Step out (Shift+F8)            ║
                    ║     POST /debug/resume      - Resume (F9)                    ║
                    ║     GET  /debug/breakpoints - List breakpoints               ║
                    ║                                                              ║
                    ║   Ready to connect AI assistants!                            ║
                    ╚══════════════════════════════════════════════════════════════╝

                """.trimIndent())
            }
        }
    }
}
