package com.morpheus.debugbridge.service

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Shared utility functions for file operations.
 * Centralizes file finding logic and security validations.
 */
object FileUtils {

    /**
     * Find a file by name or path within a project.
     * Searches in order: direct path, project-relative, common source dirs, recursive.
     */
    fun findFile(filePath: String, project: Project): VirtualFile? {
        // Sanitize the path first
        val sanitizedPath = sanitizePath(filePath)
        if (sanitizedPath == null) {
            return null // Path contained traversal attempt
        }

        // Try direct path first
        var file = LocalFileSystem.getInstance().findFileByPath(sanitizedPath)
        if (file != null) return file

        // Try relative to project
        val basePath = project.basePath ?: return null
        file = LocalFileSystem.getInstance().findFileByPath("$basePath/$sanitizedPath")
        if (file != null) return file

        // Try common source directories
        for (srcDir in listOf("src/main/java", "src/main/kotlin", "src/test/java", "src/test/kotlin", "src")) {
            file = LocalFileSystem.getInstance().findFileByPath("$basePath/$srcDir/$sanitizedPath")
            if (file != null) return file
        }

        // Search recursively by filename
        val root = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return null
        return findFileRecursively(root, sanitizedPath)
    }

    /**
     * Recursively search for a file by name.
     * Skips hidden directories and build output directories.
     */
    private fun findFileRecursively(dir: VirtualFile, fileName: String): VirtualFile? {
        for (child in dir.children) {
            if (child.isDirectory) {
                // Skip hidden, build output, and dependency directories
                if (child.name.startsWith(".") ||
                    child.name == "build" ||
                    child.name == "target" ||
                    child.name == "node_modules" ||
                    child.name == "out") {
                    continue
                }
                val found = findFileRecursively(child, fileName)
                if (found != null) return found
            } else {
                val matches = child.name == fileName ||
                    child.path.endsWith("/$fileName") ||
                    child.path.endsWith("\\$fileName")
                if (matches) return child
            }
        }
        return null
    }

    /**
     * Sanitize file path to prevent path traversal attacks.
     * Returns null if path is suspicious.
     */
    fun sanitizePath(path: String): String? {
        // Check for null bytes (classic injection technique)
        if (path.contains('\u0000')) {
            return null
        }

        // Normalize and check for path traversal
        val normalized = path
            .replace("\\", "/")
            .replace("//", "/")

        // Block explicit traversal patterns
        if (normalized.contains("../") ||
            normalized.contains("..\\") ||
            normalized == ".." ||
            normalized.startsWith("..")) {
            return null
        }

        // Block absolute paths that look suspicious (outside normal project paths)
        if (normalized.startsWith("/etc/") ||
            normalized.startsWith("/var/") ||
            normalized.startsWith("/usr/") ||
            normalized.startsWith("/root") ||
            normalized.startsWith("/home/") && !normalized.contains("/workspace/") ||
            normalized.contains("/.ssh/") ||
            normalized.contains("/.aws/") ||
            normalized.contains("/.kube/") ||
            normalized.contains("/.config/")) {
            return null
        }

        return normalized
    }

    /**
     * Validate line number is within document bounds.
     * Returns clamped value or null if invalid.
     */
    fun validateLineNumber(line: Int?, document: Document?): Int? {
        if (line == null) return null
        if (line < 1) return 1

        val maxLine = document?.lineCount ?: Int.MAX_VALUE
        return if (line > maxLine) maxLine else line
    }

    /**
     * Validate column number is within line bounds.
     * Returns clamped value or null if invalid.
     */
    fun validateColumnNumber(column: Int?, line: Int?, document: Document?): Int? {
        if (column == null) return null
        if (column < 1) return 1

        if (document == null || line == null) return column

        val lineIndex = (line - 1).coerceAtLeast(0)
        if (lineIndex >= document.lineCount) return column

        val lineEndOffset = document.getLineEndOffset(lineIndex)
        val lineStartOffset = document.getLineStartOffset(lineIndex)
        val lineLength = lineEndOffset - lineStartOffset

        return if (column > lineLength + 1) lineLength + 1 else column
    }

    /**
     * Check if file is within project boundaries.
     */
    fun isFileInProject(file: VirtualFile, project: Project): Boolean {
        val basePath = project.basePath ?: return false
        return file.path.startsWith(basePath)
    }
}
