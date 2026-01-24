package com.morpheus.debugbridge.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.morpheus.debugbridge.model.FileOperationResponse
import com.morpheus.debugbridge.model.OpenFilesResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Service for file operations in IntelliJ editor.
 * Uses FileUtils for shared logic and security validations.
 */
object FileService {

    private val log = Logger.getInstance(FileService::class.java)

    /**
     * Open a file in the IntelliJ editor.
     * Validates line/column bounds and sanitizes file path.
     */
    fun openFile(fileName: String, line: Int? = null, column: Int? = null): FileOperationResponse {
        // Validate path early
        val sanitizedPath = FileUtils.sanitizePath(fileName)
        if (sanitizedPath == null) {
            return FileOperationResponse(
                success = false,
                error = "Invalid file path: contains suspicious characters or traversal patterns"
            )
        }

        val result = AtomicReference<FileOperationResponse>()
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                val projects = ProjectManager.getInstance().openProjects
                val project = projects.firstOrNull { !it.isDefault }

                if (project == null) {
                    result.set(FileOperationResponse(
                        success = false,
                        error = "No open project"
                    ))
                    latch.countDown()
                    return@invokeLater
                }

                // Find the file using shared utility
                val file = FileUtils.findFile(sanitizedPath, project)
                if (file == null) {
                    result.set(FileOperationResponse(
                        success = false,
                        error = "File not found: $fileName"
                    ))
                    latch.countDown()
                    return@invokeLater
                }

                // Get document to validate line/column bounds
                val document = FileDocumentManager.getInstance().getDocument(file)
                val validatedLine = FileUtils.validateLineNumber(line, document)
                val validatedColumn = FileUtils.validateColumnNumber(column, validatedLine, document)

                // Create file descriptor with validated line/column
                val descriptor = if (validatedLine != null) {
                    val lineIndex = (validatedLine - 1).coerceAtLeast(0)
                    val colIndex = (validatedColumn?.minus(1) ?: 0).coerceAtLeast(0)
                    OpenFileDescriptor(project, file, lineIndex, colIndex)
                } else {
                    OpenFileDescriptor(project, file)
                }

                // Open the file
                val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)

                if (editor != null) {
                    log.info("Opened file: ${file.path}${if (validatedLine != null) " at line $validatedLine" else ""}")
                    result.set(FileOperationResponse(
                        success = true,
                        message = buildOpenMessage(line, validatedLine, column, validatedColumn),
                        file = file.name,
                        filePath = file.path,
                        line = validatedLine,
                        column = validatedColumn
                    ))
                } else {
                    // File may still be opened even if editor is null (e.g., binary file)
                    FileEditorManager.getInstance(project).openFile(file, true)
                    result.set(FileOperationResponse(
                        success = true,
                        message = "File opened (non-text editor)",
                        file = file.name,
                        filePath = file.path
                    ))
                }

            } catch (e: Exception) {
                log.error("Error opening file", e)
                result.set(FileOperationResponse(
                    success = false,
                    error = "Error: ${e.message}"
                ))
            } finally {
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        return result.get() ?: FileOperationResponse(
            success = false,
            error = "Timeout opening file"
        )
    }

    /**
     * Close a file in the editor
     */
    fun closeFile(fileName: String): FileOperationResponse {
        val sanitizedPath = FileUtils.sanitizePath(fileName)
        if (sanitizedPath == null) {
            return FileOperationResponse(
                success = false,
                error = "Invalid file path"
            )
        }

        val result = AtomicReference<FileOperationResponse>()
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                val projects = ProjectManager.getInstance().openProjects
                val project = projects.firstOrNull { !it.isDefault }

                if (project == null) {
                    result.set(FileOperationResponse(
                        success = false,
                        error = "No open project"
                    ))
                    latch.countDown()
                    return@invokeLater
                }

                // Find the file using shared utility
                val file = FileUtils.findFile(sanitizedPath, project)
                if (file == null) {
                    result.set(FileOperationResponse(
                        success = false,
                        error = "File not found: $fileName"
                    ))
                    latch.countDown()
                    return@invokeLater
                }

                FileEditorManager.getInstance(project).closeFile(file)
                log.info("Closed file: ${file.path}")
                result.set(FileOperationResponse(
                    success = true,
                    message = "File closed",
                    file = file.name,
                    filePath = file.path
                ))

            } catch (e: Exception) {
                log.error("Error closing file", e)
                result.set(FileOperationResponse(
                    success = false,
                    error = "Error: ${e.message}"
                ))
            } finally {
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        return result.get() ?: FileOperationResponse(
            success = false,
            error = "Timeout closing file"
        )
    }

    /**
     * Get list of currently open files
     */
    fun getOpenFiles(): OpenFilesResponse {
        val result = AtomicReference<OpenFilesResponse>()
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                val projects = ProjectManager.getInstance().openProjects
                val project = projects.firstOrNull { !it.isDefault }

                if (project == null) {
                    result.set(OpenFilesResponse(
                        files = emptyList(),
                        error = "No open project"
                    ))
                    latch.countDown()
                    return@invokeLater
                }

                val fileEditorManager = FileEditorManager.getInstance(project)
                val openFiles = fileEditorManager.openFiles
                val selectedFile = fileEditorManager.selectedFiles.firstOrNull()

                result.set(OpenFilesResponse(
                    files = openFiles.map { file ->
                        mapOf(
                            "name" to file.name,
                            "path" to file.path,
                            "isSelected" to (file == selectedFile),
                            "isWritable" to file.isWritable,
                            "fileType" to file.fileType.name
                        )
                    },
                    selectedFile = selectedFile?.name,
                    totalOpen = openFiles.size
                ))

            } catch (e: Exception) {
                log.error("Error getting open files", e)
                result.set(OpenFilesResponse(
                    files = emptyList(),
                    error = "Error: ${e.message}"
                ))
            } finally {
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        return result.get() ?: OpenFilesResponse(
            files = emptyList(),
            error = "Timeout getting open files"
        )
    }

    /**
     * Navigate to a specific line in the currently active file.
     * Validates line/column bounds.
     */
    fun goToLine(line: Int, column: Int? = null): FileOperationResponse {
        // Early validation for obviously bad values
        if (line < 1) {
            return FileOperationResponse(
                success = false,
                error = "Line number must be >= 1"
            )
        }

        val result = AtomicReference<FileOperationResponse>()
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                val projects = ProjectManager.getInstance().openProjects
                val project = projects.firstOrNull { !it.isDefault }

                if (project == null) {
                    result.set(FileOperationResponse(
                        success = false,
                        error = "No open project"
                    ))
                    latch.countDown()
                    return@invokeLater
                }

                val fileEditorManager = FileEditorManager.getInstance(project)
                val selectedFile = fileEditorManager.selectedFiles.firstOrNull()

                if (selectedFile == null) {
                    result.set(FileOperationResponse(
                        success = false,
                        error = "No file is currently selected"
                    ))
                    latch.countDown()
                    return@invokeLater
                }

                // Get document to validate bounds
                val document = FileDocumentManager.getInstance().getDocument(selectedFile)
                val validatedLine = FileUtils.validateLineNumber(line, document) ?: line
                val validatedColumn = FileUtils.validateColumnNumber(column, validatedLine, document)

                val lineIndex = (validatedLine - 1).coerceAtLeast(0)
                val colIndex = (validatedColumn?.minus(1) ?: 0).coerceAtLeast(0)

                val descriptor = OpenFileDescriptor(project, selectedFile, lineIndex, colIndex)
                descriptor.navigate(true)

                // Build message indicating if bounds were adjusted
                val message = buildNavigateMessage(line, validatedLine, column, validatedColumn)

                result.set(FileOperationResponse(
                    success = true,
                    message = message,
                    file = selectedFile.name,
                    filePath = selectedFile.path,
                    line = validatedLine,
                    column = validatedColumn
                ))

            } catch (e: Exception) {
                log.error("Error navigating to line", e)
                result.set(FileOperationResponse(
                    success = false,
                    error = "Error: ${e.message}"
                ))
            } finally {
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        return result.get() ?: FileOperationResponse(
            success = false,
            error = "Timeout navigating to line"
        )
    }

    /**
     * Build message for open operation, noting any bound adjustments
     */
    private fun buildOpenMessage(
        requestedLine: Int?,
        actualLine: Int?,
        requestedColumn: Int?,
        actualColumn: Int?
    ): String {
        val adjustments = mutableListOf<String>()

        if (requestedLine != null && actualLine != null && requestedLine != actualLine) {
            adjustments.add("line adjusted from $requestedLine to $actualLine")
        }
        if (requestedColumn != null && actualColumn != null && requestedColumn != actualColumn) {
            adjustments.add("column adjusted from $requestedColumn to $actualColumn")
        }

        return if (adjustments.isEmpty()) {
            "File opened successfully"
        } else {
            "File opened (${adjustments.joinToString(", ")})"
        }
    }

    /**
     * Build message for navigate operation, noting any bound adjustments
     */
    private fun buildNavigateMessage(
        requestedLine: Int,
        actualLine: Int,
        requestedColumn: Int?,
        actualColumn: Int?
    ): String {
        val adjustments = mutableListOf<String>()

        if (requestedLine != actualLine) {
            adjustments.add("line clamped from $requestedLine to $actualLine")
        }
        if (requestedColumn != null && actualColumn != null && requestedColumn != actualColumn) {
            adjustments.add("column clamped from $requestedColumn to $actualColumn")
        }

        return if (adjustments.isEmpty()) {
            "Navigated to line $actualLine"
        } else {
            "Navigated to line $actualLine (${adjustments.joinToString(", ")})"
        }
    }
}
