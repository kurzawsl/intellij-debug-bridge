package com.morpheus.debugbridge.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.morpheus.debugbridge.config.TimeoutConfig
import com.morpheus.debugbridge.model.BreakpointInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages breakpoints across all open projects.
 */
object BreakpointManager {

    private val log = Logger.getInstance(BreakpointManager::class.java)

    /**
     * Get all line breakpoints from all open projects
     */
    fun getAllBreakpoints(): List<BreakpointInfo> {
        val breakpoints = mutableListOf<BreakpointInfo>()
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                ReadAction.run<Exception> {
                    val projects = ProjectManager.getInstance().openProjects
                    for (project in projects) {
                        if (project.isDefault) continue

                        try {
                            val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
                            val allBreakpoints = breakpointManager.allBreakpoints

                            for (bp in allBreakpoints) {
                                if (bp is XLineBreakpoint<*>) {
                                    val file = bp.sourcePosition?.file
                                    val line = bp.sourcePosition?.line?.plus(1) ?: bp.line + 1

                                    breakpoints.add(
                                        BreakpointInfo(
                                            file = file?.name ?: "unknown",
                                            line = line,
                                            enabled = bp.isEnabled,
                                            condition = bp.conditionExpression?.expression
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            log.warn("Error getting breakpoints for project ${project.name}: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                log.error("Error getting breakpoints", e)
            } finally {
                latch.countDown()
            }
        }

        latch.await(TimeoutConfig.BREAKPOINT_OPERATION_MS, TimeUnit.MILLISECONDS)
        return breakpoints
    }

    /**
     * Add a line breakpoint to a specific project (V2 API)
     */
    fun addBreakpointToProject(project: com.intellij.openapi.project.Project, filePath: String, line: Int, condition: String? = null): Boolean {
        val success = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                // Find the file in this specific project
                val file = findFileInProject(filePath, project)
                if (file == null) {
                    log.warn("File not found in project ${project.name}: $filePath")
                    latch.countDown()
                    return@invokeLater
                }

                addBreakpointInternal(project, file, line, condition, success)
            } catch (e: Exception) {
                log.error("Error adding breakpoint to project", e)
            } finally {
                latch.countDown()
            }
        }

        latch.await(TimeoutConfig.BREAKPOINT_OPERATION_MS, TimeUnit.MILLISECONDS)
        return success.get()
    }

    /**
     * Add a line breakpoint (V1 API - uses first project that contains the file)
     * @param filePath Absolute path to the file or filename to search for
     * @param line Line number (1-based)
     * @param condition Optional condition expression
     */
    fun addBreakpoint(filePath: String, line: Int, condition: String? = null): Boolean {
        val success = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                // Find the file
                val file = findFile(filePath)
                if (file == null) {
                    log.warn("File not found: $filePath")
                    latch.countDown()
                    return@invokeLater
                }

                // Find a project that contains this file
                val projects = ProjectManager.getInstance().openProjects
                val project = projects.firstOrNull { !it.isDefault } ?: run {
                    log.warn("No open project found")
                    latch.countDown()
                    return@invokeLater
                }

                val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager

                // Check if breakpoint already exists at this location
                val existingBreakpoint = breakpointManager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>().find { bp ->
                    val bpFile = bp.sourcePosition?.file ?: return@find false
                    val bpLine = bp.sourcePosition?.line?.plus(1) ?: (bp.line + 1)
                    (bpFile.path == file.path || bpFile.name == file.name) && bpLine == line
                }

                if (existingBreakpoint != null) {
                    log.info("Breakpoint already exists at ${file.name}:$line")
                    success.set(true) // Return success since breakpoint exists
                    latch.countDown()
                    return@invokeLater
                }

                // Find the appropriate line breakpoint type
                val breakpointType = findLineBreakpointType(file)
                if (breakpointType == null) {
                    log.warn("No suitable breakpoint type found for file: ${file.name}")
                    latch.countDown()
                    return@invokeLater
                }

                // Add the breakpoint (line is 1-based, API expects 0-based)
                val lineIndex = line - 1

                ApplicationManager.getApplication().runWriteAction {
                    try {
                        // Create default properties for the breakpoint type
                        val properties = breakpointType.createBreakpointProperties(file, lineIndex)
                        log.info("Created breakpoint properties: $properties for type: ${breakpointType.id}")

                        // Add breakpoint using raw types to avoid generic issues
                        val breakpoint = addBreakpointRaw(breakpointManager, breakpointType, file.url, lineIndex, properties)

                        if (breakpoint != null) {
                            // Set condition if provided
                            if (!condition.isNullOrBlank()) {
                                breakpoint.conditionExpression =
                                    com.intellij.xdebugger.XDebuggerUtil.getInstance().createExpression(
                                        condition, null, null, com.intellij.xdebugger.evaluation.EvaluationMode.EXPRESSION
                                    )
                            }
                            log.info("Breakpoint added at ${file.name}:$line")
                            success.set(true)
                        } else {
                            log.warn("Failed to add breakpoint - addLineBreakpoint returned null")
                        }
                    } catch (e: Exception) {
                        log.error("Failed to add breakpoint", e)
                    }
                }

            } catch (e: Exception) {
                log.error("Error adding breakpoint", e)
            } finally {
                latch.countDown()
            }
        }

        latch.await(TimeoutConfig.BREAKPOINT_OPERATION_MS, TimeUnit.MILLISECONDS)
        return success.get()
    }

    /**
     * Remove a line breakpoint
     * @param filePath Absolute path to the file or filename to search for
     * @param line Line number (1-based)
     */
    fun removeBreakpoint(filePath: String, line: Int): Boolean {
        val success = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                val projects = ProjectManager.getInstance().openProjects

                for (project in projects) {
                    if (project.isDefault) continue

                    val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
                    val allBreakpoints = breakpointManager.allBreakpoints

                    for (bp in allBreakpoints) {
                        if (bp is XLineBreakpoint<*>) {
                            val bpFile = bp.sourcePosition?.file
                            val bpLine = bp.sourcePosition?.line?.plus(1) ?: (bp.line + 1)

                            // Match by filename or full path
                            val fileMatches = bpFile?.let {
                                it.name == filePath ||
                                it.path == filePath ||
                                it.path.endsWith(filePath)
                            } ?: false

                            if (fileMatches && bpLine == line) {
                                ApplicationManager.getApplication().runWriteAction {
                                    breakpointManager.removeBreakpoint(bp)
                                }
                                log.info("Breakpoint removed at ${bpFile?.name}:$line")
                                success.set(true)
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                log.error("Error removing breakpoint", e)
            } finally {
                latch.countDown()
            }
        }

        latch.await(TimeoutConfig.BREAKPOINT_OPERATION_MS, TimeUnit.MILLISECONDS)
        return success.get()
    }

    /**
     * Toggle breakpoint enabled state
     */
    fun toggleBreakpoint(filePath: String, line: Int): Boolean {
        val success = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                val projects = ProjectManager.getInstance().openProjects

                for (project in projects) {
                    if (project.isDefault) continue

                    val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
                    val allBreakpoints = breakpointManager.allBreakpoints

                    for (bp in allBreakpoints) {
                        if (bp is XLineBreakpoint<*>) {
                            val bpFile = bp.sourcePosition?.file
                            val bpLine = bp.sourcePosition?.line?.plus(1) ?: (bp.line + 1)

                            val fileMatches = bpFile?.let {
                                it.name == filePath ||
                                it.path == filePath ||
                                it.path.endsWith(filePath)
                            } ?: false

                            if (fileMatches && bpLine == line) {
                                bp.isEnabled = !bp.isEnabled
                                log.info("Breakpoint toggled at ${bpFile?.name}:$line, enabled=${bp.isEnabled}")
                                success.set(true)
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                log.error("Error toggling breakpoint", e)
            } finally {
                latch.countDown()
            }
        }

        latch.await(TimeoutConfig.BREAKPOINT_OPERATION_MS, TimeUnit.MILLISECONDS)
        return success.get()
    }

    /**
     * Remove all breakpoints from all projects
     */
    fun removeAllBreakpoints(): Int {
        val removed = AtomicReference(0)
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                val projects = ProjectManager.getInstance().openProjects

                for (project in projects) {
                    if (project.isDefault) continue

                    val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
                    val allBreakpoints = breakpointManager.allBreakpoints.toList()

                    ApplicationManager.getApplication().runWriteAction {
                        for (bp in allBreakpoints) {
                            breakpointManager.removeBreakpoint(bp)
                            removed.updateAndGet { it + 1 }
                        }
                    }
                }
                log.info("Removed ${removed.get()} breakpoints")
            } catch (e: Exception) {
                log.error("Error removing all breakpoints", e)
            } finally {
                latch.countDown()
            }
        }

        latch.await(TimeoutConfig.BREAKPOINT_OPERATION_MS, TimeUnit.MILLISECONDS)
        return removed.get()
    }

    /**
     * Helper function to add breakpoint using raw types
     */
    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun addBreakpointRaw(
        breakpointManager: XBreakpointManager,
        breakpointType: XLineBreakpointType<*>,
        fileUrl: String,
        lineIndex: Int,
        properties: Any?
    ): XLineBreakpoint<*>? {
        // Cast to raw type to avoid generic issues
        val rawType = breakpointType as XLineBreakpointType<com.intellij.xdebugger.breakpoints.XBreakpointProperties<Any>>
        val rawProps = properties as? com.intellij.xdebugger.breakpoints.XBreakpointProperties<Any>

        return breakpointManager.addLineBreakpoint(rawType, fileUrl, lineIndex, rawProps)
    }

    /**
     * Find a virtual file by path or name
     */
    private fun findFile(filePath: String): VirtualFile? {
        // Try direct path first
        var file = LocalFileSystem.getInstance().findFileByPath(filePath)
        if (file != null) return file

        // Search in open projects
        val projects = ProjectManager.getInstance().openProjects
        for (project in projects) {
            if (project.isDefault) continue

            val basePath = project.basePath ?: continue

            // Try relative to project
            file = LocalFileSystem.getInstance().findFileByPath("$basePath/$filePath")
            if (file != null) return file

            // Try common source directories
            for (srcDir in listOf("src/main/java", "src/main/kotlin", "src/test/java", "src/test/kotlin", "src")) {
                file = LocalFileSystem.getInstance().findFileByPath("$basePath/$srcDir/$filePath")
                if (file != null) return file
            }
        }

        // Search recursively in project roots (last resort)
        for (project in projects) {
            if (project.isDefault) continue
            val basePath = project.basePath ?: continue
            val root = LocalFileSystem.getInstance().findFileByPath(basePath) ?: continue

            val found = findFileRecursively(root, filePath)
            if (found != null) return found
        }

        return null
    }

    private fun findFileRecursively(dir: VirtualFile, fileName: String): VirtualFile? {
        for (child in dir.children) {
            if (child.isDirectory) {
                if (child.name.startsWith(".")) continue // Skip hidden dirs
                val found = findFileRecursively(child, fileName)
                if (found != null) return found
            } else {
                // FIXED: Only match exact file name OR proper path suffix (with separator)
                // This prevents "OrganisationPersistenceFactory.java" from matching
                // "UserOrganisationPersistenceFactory.java"
                val matches = child.name == fileName ||
                    child.path.endsWith("/$fileName") ||
                    child.path.endsWith("\\$fileName")
                if (matches) {
                    log.info("Found file: ${child.path} for search: $fileName")
                    return child
                }
            }
        }
        return null
    }

    /**
     * Find the appropriate line breakpoint type for a file
     */
    private fun findLineBreakpointType(file: VirtualFile): XLineBreakpointType<*>? {
        val extension = file.extension?.lowercase()

        // Get all registered line breakpoint types
        val allTypes = com.intellij.xdebugger.breakpoints.XBreakpointType.EXTENSION_POINT_NAME.extensionList

        // Filter to only line breakpoint types
        val lineBreakpointTypes = allTypes.filterIsInstance<XLineBreakpointType<*>>()

        // Log available types for debugging
        log.info("Available line breakpoint types: ${lineBreakpointTypes.map { it.id }}")

        // Find by file extension - use exact ID match for reliability
        val typeByExtension = when (extension) {
            "java" -> lineBreakpointTypes.find { it.id == "java-line" }
            "kt", "kts" -> lineBreakpointTypes.find { it.id == "kotlin-line" }
            else -> null
        }

        if (typeByExtension != null) {
            log.info("Selected breakpoint type: ${typeByExtension.id} for file extension: $extension")
            return typeByExtension
        }

        // Fallback: return the first line breakpoint type that's not exception-based
        val fallback = lineBreakpointTypes.firstOrNull {
            it.id == "java-line" || it.id == "kotlin-line"
        }
        log.info("Using fallback breakpoint type: ${fallback?.id}")
        return fallback
    }

    /**
     * Find a file within a specific project
     */
    private fun findFileInProject(filePath: String, project: com.intellij.openapi.project.Project): VirtualFile? {
        val basePath = project.basePath ?: return null

        // Try direct path first
        var file = LocalFileSystem.getInstance().findFileByPath(filePath)
        if (file != null) return file

        // Try relative to project
        file = LocalFileSystem.getInstance().findFileByPath("$basePath/$filePath")
        if (file != null) return file

        // Try common source directories
        for (srcDir in listOf("src/main/java", "src/main/kotlin", "src/test/java", "src/test/kotlin", "src")) {
            file = LocalFileSystem.getInstance().findFileByPath("$basePath/$srcDir/$filePath")
            if (file != null) return file
        }

        // Search recursively in project root
        val root = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return null
        return findFileRecursively(root, filePath)
    }

    /**
     * Internal method to add breakpoint - called from both V1 and V2 APIs
     */
    private fun addBreakpointInternal(
        project: com.intellij.openapi.project.Project,
        file: VirtualFile,
        line: Int,
        condition: String?,
        success: AtomicBoolean
    ) {
        val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager

        // Check if breakpoint already exists at this location
        val existingBreakpoint = breakpointManager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>().find { bp ->
            val bpFile = bp.sourcePosition?.file ?: return@find false
            val bpLine = bp.sourcePosition?.line?.plus(1) ?: (bp.line + 1)
            (bpFile.path == file.path || bpFile.name == file.name) && bpLine == line
        }

        if (existingBreakpoint != null) {
            log.info("Breakpoint already exists at ${file.name}:$line")
            success.set(true)
            return
        }

        // Find the appropriate line breakpoint type
        val breakpointType = findLineBreakpointType(file)
        if (breakpointType == null) {
            log.warn("No suitable breakpoint type found for file: ${file.name}")
            return
        }

        // Add the breakpoint (line is 1-based, API expects 0-based)
        val lineIndex = line - 1

        ApplicationManager.getApplication().runWriteAction {
            try {
                val properties = breakpointType.createBreakpointProperties(file, lineIndex)
                log.info("Created breakpoint properties: $properties for type: ${breakpointType.id}")

                val breakpoint = addBreakpointRaw(breakpointManager, breakpointType, file.url, lineIndex, properties)

                if (breakpoint != null) {
                    if (!condition.isNullOrBlank()) {
                        breakpoint.conditionExpression =
                            com.intellij.xdebugger.XDebuggerUtil.getInstance().createExpression(
                                condition, null, null, com.intellij.xdebugger.evaluation.EvaluationMode.EXPRESSION
                            )
                    }
                    log.info("Breakpoint added at ${file.name}:$line in project ${project.name}")
                    success.set(true)
                } else {
                    log.warn("Failed to add breakpoint - addLineBreakpoint returned null")
                }
            } catch (e: Exception) {
                log.error("Failed to add breakpoint", e)
            }
        }
    }
}
