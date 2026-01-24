package com.morpheus.debugbridge.service

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.morpheus.debugbridge.model.InspectionProblem
import com.morpheus.debugbridge.model.InspectionsResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Service for reading linter/inspection problems from IntelliJ.
 */
object InspectionService {

    private val log = Logger.getInstance(InspectionService::class.java)

    /**
     * Get inspection problems for a specific file.
     * Note: File must be open in editor for inspections to be available.
     */
    fun getProblemsForFile(fileName: String): InspectionsResponse {
        // Validate path early
        val sanitizedPath = FileUtils.sanitizePath(fileName)
        if (sanitizedPath == null) {
            return InspectionsResponse(
                problems = emptyList(),
                error = "Invalid file path: contains suspicious characters or traversal patterns"
            )
        }

        val result = AtomicReference<InspectionsResponse>()
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                val projects = ProjectManager.getInstance().openProjects
                val project = projects.firstOrNull { !it.isDefault }

                if (project == null) {
                    result.set(InspectionsResponse(
                        problems = emptyList(),
                        error = "No open project"
                    ))
                    latch.countDown()
                    return@invokeLater
                }

                // Find the file using shared utility
                val file = FileUtils.findFile(sanitizedPath, project)
                if (file == null) {
                    result.set(InspectionsResponse(
                        problems = emptyList(),
                        error = "File not found: $fileName. Make sure to provide exact filename or path."
                    ))
                    latch.countDown()
                    return@invokeLater
                }

                // Get the document and PSI file
                val document = FileDocumentManager.getInstance().getDocument(file)
                if (document == null) {
                    result.set(InspectionsResponse(
                        problems = emptyList(),
                        error = "Could not get document for file. File may need to be opened first."
                    ))
                    latch.countDown()
                    return@invokeLater
                }

                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
                if (psiFile == null) {
                    result.set(InspectionsResponse(
                        problems = emptyList(),
                        error = "Could not get PSI file. File may need to be opened first."
                    ))
                    latch.countDown()
                    return@invokeLater
                }

                // Get highlight info from daemon code analyzer
                val problems = mutableListOf<InspectionProblem>()

                try {
                    val highlights = DaemonCodeAnalyzerImpl.getHighlights(document, null, project)

                    for (info in highlights) {
                        if (info.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal) {
                            val startLine = document.getLineNumber(info.startOffset) + 1
                            val endLine = document.getLineNumber(info.endOffset) + 1
                            val startCol = info.startOffset - document.getLineStartOffset(startLine - 1)
                            val endCol = info.endOffset - document.getLineStartOffset(endLine - 1)

                            problems.add(InspectionProblem(
                                file = file.name,
                                filePath = file.path,
                                startLine = startLine,
                                endLine = endLine,
                                startColumn = startCol,
                                endColumn = endCol,
                                severity = mapSeverity(info.severity),
                                message = info.description ?: info.toolTip ?: "Unknown issue",
                                inspectionId = info.inspectionToolId,
                                quickFixAvailable = info.quickFixActionRanges?.isNotEmpty() == true
                            ))
                        }
                    }

                    result.set(InspectionsResponse(
                        problems = problems,
                        file = file.name,
                        filePath = file.path,
                        totalProblems = problems.size,
                        errorCount = problems.count { it.severity == "ERROR" },
                        warningCount = problems.count { it.severity == "WARNING" },
                        weakWarningCount = problems.count { it.severity == "WEAK_WARNING" },
                        infoCount = problems.count { it.severity == "INFO" }
                    ))

                } catch (e: Exception) {
                    log.warn("Could not get highlights, file may not be analyzed yet", e)
                    result.set(InspectionsResponse(
                        problems = emptyList(),
                        file = file.name,
                        filePath = file.path,
                        message = "File may not be analyzed yet. Try opening the file in editor first using /debug/open-file endpoint."
                    ))
                }

            } catch (e: Exception) {
                log.error("Error getting inspections", e)
                result.set(InspectionsResponse(
                    problems = emptyList(),
                    error = "Error: ${e.message}"
                ))
            } finally {
                latch.countDown()
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        return result.get() ?: InspectionsResponse(
            problems = emptyList(),
            error = "Timeout getting inspections"
        )
    }

    /**
     * Get all problems in currently open files
     */
    fun getAllOpenFileProblems(): InspectionsResponse {
        val result = AtomicReference<InspectionsResponse>()
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                val projects = ProjectManager.getInstance().openProjects
                val project = projects.firstOrNull { !it.isDefault }

                if (project == null) {
                    result.set(InspectionsResponse(
                        problems = emptyList(),
                        error = "No open project"
                    ))
                    latch.countDown()
                    return@invokeLater
                }

                val allProblems = mutableListOf<InspectionProblem>()
                val openFiles = FileEditorManager.getInstance(project).openFiles

                for (file in openFiles) {
                    val document = FileDocumentManager.getInstance().getDocument(file) ?: continue

                    try {
                        val highlights = DaemonCodeAnalyzerImpl.getHighlights(document, null, project)

                        for (info in highlights) {
                            if (info.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal) {
                                val startLine = document.getLineNumber(info.startOffset) + 1
                                val endLine = document.getLineNumber(info.endOffset) + 1
                                val startCol = info.startOffset - document.getLineStartOffset(startLine - 1)
                                val endCol = info.endOffset - document.getLineStartOffset(endLine - 1)

                                allProblems.add(InspectionProblem(
                                    file = file.name,
                                    filePath = file.path,
                                    startLine = startLine,
                                    endLine = endLine,
                                    startColumn = startCol,
                                    endColumn = endCol,
                                    severity = mapSeverity(info.severity),
                                    message = info.description ?: info.toolTip ?: "Unknown issue",
                                    inspectionId = info.inspectionToolId,
                                    quickFixAvailable = info.quickFixActionRanges?.isNotEmpty() == true
                                ))
                            }
                        }
                    } catch (e: Exception) {
                        log.debug("Could not get highlights for ${file.name}: ${e.message}")
                    }
                }

                result.set(InspectionsResponse(
                    problems = allProblems,
                    totalProblems = allProblems.size,
                    errorCount = allProblems.count { it.severity == "ERROR" },
                    warningCount = allProblems.count { it.severity == "WARNING" },
                    weakWarningCount = allProblems.count { it.severity == "WEAK_WARNING" },
                    infoCount = allProblems.count { it.severity == "INFO" },
                    openFileCount = openFiles.size
                ))

            } catch (e: Exception) {
                log.error("Error getting all problems", e)
                result.set(InspectionsResponse(
                    problems = emptyList(),
                    error = "Error: ${e.message}"
                ))
            } finally {
                latch.countDown()
            }
        }

        latch.await(15, TimeUnit.SECONDS)
        return result.get() ?: InspectionsResponse(
            problems = emptyList(),
            error = "Timeout getting all problems"
        )
    }

    private fun mapSeverity(severity: HighlightSeverity): String {
        return when {
            severity.myVal >= HighlightSeverity.ERROR.myVal -> "ERROR"
            severity.myVal >= HighlightSeverity.WARNING.myVal -> "WARNING"
            severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal -> "WEAK_WARNING"
            else -> "INFO"
        }
    }
}
