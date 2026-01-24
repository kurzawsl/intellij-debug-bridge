package com.morpheus.debugbridge.service

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.xdebugger.XDebugSession
import com.morpheus.debugbridge.model.ConsoleLogResponse
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Service for capturing and reading debug console output.
 * Maintains an in-memory buffer of console lines.
 * Thread-safe implementation using atomic operations and locks.
 */
object ConsoleLogService {

    private val log = Logger.getInstance(ConsoleLogService::class.java)

    // Ring buffer to store console lines (max 10000 lines)
    private const val MAX_BUFFER_SIZE = 10000
    private val consoleBuffer = ConcurrentLinkedDeque<ConsoleLine>()

    // Thread-safe line counter using AtomicLong
    private val lineCounter = AtomicLong(0L)

    // Lock for buffer operations that need to be atomic
    private val bufferLock = ReentrantLock()

    // Track active listeners for cleanup
    private val activeListeners = mutableMapOf<XDebugSession, com.intellij.execution.process.ProcessListener>()

    data class ConsoleLine(
        val lineNumber: Long,
        val text: String,
        val type: String, // STDOUT, STDERR, SYSTEM
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Add a line to the console buffer.
     * Thread-safe: uses atomic counter and lock for buffer trimming.
     */
    fun addLine(text: String, type: String = "STDOUT") {
        // Split by newlines and add each line separately
        text.split("\n").filter { it.isNotEmpty() }.forEach { line ->
            val consoleLine = ConsoleLine(
                lineNumber = lineCounter.getAndIncrement(),
                text = line,
                type = type
            )
            consoleBuffer.addLast(consoleLine)

            // Trim buffer if too large - use lock to avoid race condition
            if (consoleBuffer.size > MAX_BUFFER_SIZE) {
                bufferLock.withLock {
                    while (consoleBuffer.size > MAX_BUFFER_SIZE) {
                        consoleBuffer.pollFirst()
                    }
                }
            }
        }
    }

    /**
     * Clear the console buffer.
     * Thread-safe: uses lock to ensure atomic clear operation.
     */
    fun clear() {
        bufferLock.withLock {
            consoleBuffer.clear()
            lineCounter.set(0)
        }
    }

    /**
     * Get console lines with various options
     */
    fun getLines(
        lastN: Int? = null,
        fromLine: Int? = null,
        toLine: Int? = null,
        filter: String? = null,
        type: String? = null
    ): ConsoleLogResponse {
        var lines = consoleBuffer.toList()

        // Filter by type
        if (type != null) {
            lines = lines.filter { it.type.equals(type, ignoreCase = true) }
        }

        // Filter by text content
        if (!filter.isNullOrBlank()) {
            lines = lines.filter { it.text.contains(filter, ignoreCase = true) }
        }

        // Get line range
        if (fromLine != null && toLine != null) {
            lines = lines.filter { it.lineNumber >= fromLine && it.lineNumber <= toLine }
        } else if (lastN != null) {
            lines = lines.takeLast(lastN)
        }

        return ConsoleLogResponse(
            lines = lines.map { mapOf(
                "line" to it.lineNumber,
                "text" to it.text,
                "type" to it.type,
                "timestamp" to it.timestamp
            )},
            totalLines = consoleBuffer.size,
            bufferStartLine = consoleBuffer.firstOrNull()?.lineNumber ?: 0,
            bufferEndLine = consoleBuffer.lastOrNull()?.lineNumber ?: 0
        )
    }

    /**
     * Get current console output directly from IntelliJ's console view.
     * This is a fallback/alternative to the buffer.
     */
    fun getCurrentConsoleOutput(): ConsoleLogResponse {
        val result = AtomicReference<ConsoleLogResponse>()
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().invokeLater {
            try {
                val projects = ProjectManager.getInstance().openProjects
                val project = projects.firstOrNull { !it.isDefault }

                if (project == null) {
                    result.set(ConsoleLogResponse(
                        lines = emptyList(),
                        totalLines = 0,
                        error = "No open project"
                    ))
                    latch.countDown()
                    return@invokeLater
                }

                // Get the active debug session's console
                val session = DebugStateService.getInstance().getActiveSession()
                if (session == null) {
                    result.set(ConsoleLogResponse(
                        lines = emptyList(),
                        totalLines = 0,
                        error = "No active debug session"
                    ))
                    latch.countDown()
                    return@invokeLater
                }

                // Get console from run content
                val runContentDescriptor = session.runContentDescriptor
                val consoleView = runContentDescriptor?.executionConsole

                // If we have buffered output, return it
                if (consoleBuffer.isNotEmpty()) {
                    result.set(getLines(lastN = 100))
                } else {
                    result.set(ConsoleLogResponse(
                        lines = emptyList(),
                        totalLines = 0,
                        message = "Console buffer is empty. Output is captured when the debug session produces output."
                    ))
                }

            } catch (e: Exception) {
                log.error("Error getting console output", e)
                result.set(ConsoleLogResponse(
                    lines = emptyList(),
                    totalLines = 0,
                    error = "Error: ${e.message}"
                ))
            } finally {
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        return result.get() ?: ConsoleLogResponse(
            lines = emptyList(),
            totalLines = 0,
            error = "Timeout getting console output"
        )
    }

    /**
     * Attach to debug session to capture console output.
     * Stores listener reference for cleanup when session ends.
     * Uses delayed execution because runContentDescriptor may not be ready immediately.
     */
    fun attachToSession(session: XDebugSession) {
        // Schedule attachment with delay - runContentDescriptor isn't ready in processStarted callback
        ApplicationManager.getApplication().invokeLater {
            attachToSessionInternal(session, retryCount = 0)
        }
    }

    private fun attachToSessionInternal(session: XDebugSession, retryCount: Int) {
        try {
            // Remove existing listener if any (shouldn't happen, but be safe)
            detachFromSession(session)

            val processHandler = session.runContentDescriptor?.processHandler
            if (processHandler != null) {
                val listener = object : com.intellij.execution.process.ProcessListener {
                    override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: Key<*>) {
                        val type = when {
                            outputType === ProcessOutputTypes.STDOUT -> "STDOUT"
                            outputType === ProcessOutputTypes.STDERR -> "STDERR"
                            outputType === ProcessOutputTypes.SYSTEM -> "SYSTEM"
                            else -> "OTHER"
                        }
                        addLine(event.text, type)
                    }

                    override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                        addLine("=== Process terminated with exit code ${event.exitCode} ===", "SYSTEM")
                        // Clean up listener reference
                        detachFromSession(session)
                    }

                    override fun startNotified(event: com.intellij.execution.process.ProcessEvent) {
                        addLine("=== Process started ===", "SYSTEM")
                    }
                }

                // Store reference for cleanup
                synchronized(activeListeners) {
                    activeListeners[session] = listener
                }
                processHandler.addProcessListener(listener)
                log.info("Attached console listener to debug session: ${session.sessionName}")
            } else if (retryCount < 5) {
                // Retry with delay - runContentDescriptor may not be ready yet
                log.info("Process handler not ready, retrying attachment (attempt ${retryCount + 1}/5)...")
                Thread.sleep(100)
                ApplicationManager.getApplication().invokeLater {
                    attachToSessionInternal(session, retryCount + 1)
                }
            } else {
                log.warn("No process handler available for session after 5 retries: ${session.sessionName}")
            }
        } catch (e: Exception) {
            if (retryCount < 5) {
                log.info("Attachment failed, retrying (attempt ${retryCount + 1}/5)...")
                Thread.sleep(100)
                ApplicationManager.getApplication().invokeLater {
                    attachToSessionInternal(session, retryCount + 1)
                }
            } else {
                log.error("Failed to attach console listener after 5 retries", e)
            }
        }
    }

    /**
     * Detach listener from session and clean up.
     */
    fun detachFromSession(session: XDebugSession) {
        try {
            val listener = synchronized(activeListeners) {
                activeListeners.remove(session)
            }
            if (listener != null) {
                session.runContentDescriptor?.processHandler?.removeProcessListener(listener)
                log.info("Detached console listener from session: ${session.sessionName}")
            }
        } catch (e: Exception) {
            log.warn("Error detaching console listener", e)
        }
    }

    /**
     * Clean up all listeners (call on plugin shutdown).
     */
    fun cleanupAll() {
        synchronized(activeListeners) {
            activeListeners.keys.toList().forEach { session ->
                detachFromSession(session)
            }
        }
    }
}
