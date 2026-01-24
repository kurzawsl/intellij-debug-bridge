package com.morpheus.debugbridge

import com.morpheus.debugbridge.model.*
import io.mockk.*
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XSourcePosition

/**
 * Test utilities and mock factories for IntelliJ plugin testing.
 */
object TestHelpers {

    /**
     * Create a mock XDebugSession with configurable state
     */
    fun createMockSession(
        name: String = "TestSession",
        isSuspended: Boolean = true,
        isPaused: Boolean = true,
        currentFrame: XStackFrame? = null,
        suspendContext: XSuspendContext? = null
    ): XDebugSession {
        val session = mockk<XDebugSession>(relaxed = true)

        every { session.sessionName } returns name
        every { session.isSuspended } returns isSuspended
        every { session.isPaused } returns isPaused
        every { session.currentStackFrame } returns currentFrame
        every { session.suspendContext } returns suspendContext

        return session
    }

    /**
     * Create a mock XStackFrame with source position
     */
    fun createMockStackFrame(
        fileName: String = "Test.java",
        filePath: String = "/src/Test.java",
        line: Int = 10
    ): XStackFrame {
        val frame = mockk<XStackFrame>(relaxed = true)
        val sourcePosition = createMockSourcePosition(fileName, filePath, line)

        every { frame.sourcePosition } returns sourcePosition

        return frame
    }

    /**
     * Create a mock XSourcePosition
     */
    fun createMockSourcePosition(
        fileName: String = "Test.java",
        filePath: String = "/src/Test.java",
        line: Int = 10
    ): XSourcePosition {
        val virtualFile = mockk<VirtualFile>(relaxed = true)
        every { virtualFile.name } returns fileName
        every { virtualFile.path } returns filePath

        val sourcePosition = mockk<XSourcePosition>(relaxed = true)
        every { sourcePosition.file } returns virtualFile
        every { sourcePosition.line } returns line - 1 // 0-based internally

        return sourcePosition
    }

    /**
     * Create a mock XSuspendContext
     */
    fun createMockSuspendContext(
        executionStack: XExecutionStack? = null
    ): XSuspendContext {
        val context = mockk<XSuspendContext>(relaxed = true)
        every { context.activeExecutionStack } returns executionStack
        return context
    }

    /**
     * Create a mock XExecutionStack
     */
    fun createMockExecutionStack(): XExecutionStack {
        return mockk<XExecutionStack>(relaxed = true)
    }

    /**
     * Create sample DebugState for testing
     */
    fun createSampleDebugState(
        status: DebugStatus = DebugStatus.SUSPENDED,
        sessionName: String? = "TestApp",
        fileName: String = "App.java",
        line: Int = 42
    ): DebugState {
        return DebugState(
            status = status,
            sessionName = sessionName,
            location = if (status == DebugStatus.NO_SESSION) null else DebugLocation(
                file = fileName,
                filePath = "/src/main/java/$fileName",
                line = line,
                method = "testMethod",
                className = "com.example.${fileName.removeSuffix(".java")}"
            ),
            stackTrace = if (status == DebugStatus.SUSPENDED) {
                listOf(
                    StackFrameInfo(0, "testMethod", fileName, line, "com.example.App"),
                    StackFrameInfo(1, "main", "Main.java", 10, "com.example.Main")
                )
            } else emptyList()
        )
    }

    /**
     * Create sample VariablesResponse
     */
    fun createSampleVariablesResponse(): VariablesResponse {
        return VariablesResponse(
            locals = listOf(
                VariableInfo("count", "int", "42", false),
                VariableInfo("name", "String", "\"John\"", false),
                VariableInfo("user", "User", "User@12345", true),
                VariableInfo("items", "List", "{size=3}", true)
            ),
            thisObject = ObjectInfo(
                type = "TestClass",
                id = "@98765",
                fields = listOf(
                    VariableInfo("field1", "String", "value1"),
                    VariableInfo("field2", "int", "100")
                )
            )
        )
    }

    /**
     * Create sample breakpoints
     */
    fun createSampleBreakpoints(): List<BreakpointInfo> {
        return listOf(
            BreakpointInfo("Main.java", 10, enabled = true),
            BreakpointInfo("Service.java", 25, enabled = true, condition = "x > 0"),
            BreakpointInfo("Handler.java", 100, enabled = false)
        )
    }

    /**
     * Create sample ApiResponse for success
     */
    fun createSuccessResponse(
        message: String = "Operation successful",
        state: DebugState? = null
    ): ApiResponse {
        return ApiResponse(
            success = true,
            message = message,
            state = state ?: createSampleDebugState()
        )
    }

    /**
     * Create sample ApiResponse for failure
     */
    fun createFailureResponse(
        message: String = "Operation failed"
    ): ApiResponse {
        return ApiResponse(
            success = false,
            message = message,
            state = null
        )
    }
}

/**
 * Extension functions for testing
 */
fun DebugState.assertSuspended() {
    assert(this.status == DebugStatus.SUSPENDED) {
        "Expected SUSPENDED but was ${this.status}"
    }
}

fun DebugState.assertRunning() {
    assert(this.status == DebugStatus.RUNNING) {
        "Expected RUNNING but was ${this.status}"
    }
}

fun DebugState.assertNoSession() {
    assert(this.status == DebugStatus.NO_SESSION) {
        "Expected NO_SESSION but was ${this.status}"
    }
}

fun ApiResponse.assertSuccess() {
    assert(this.success) { "Expected success but was failure: ${this.message}" }
}

fun ApiResponse.assertFailure() {
    assert(!this.success) { "Expected failure but was success" }
}
