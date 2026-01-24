package com.morpheus.debugbridge.service

import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XSourcePosition
import com.morpheus.debugbridge.model.*
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for DebugStateService.
 *
 * Note: DebugStateService is an IntelliJ application-level service.
 * In real IntelliJ plugin tests, it would be accessed via ApplicationManager.
 * For unit testing, we test the logic directly using reflection to create instances.
 */
@DisplayName("DebugStateService")
class DebugStateServiceTest {

    private lateinit var service: DebugStateService

    @BeforeEach
    fun setUp() {
        // Create a fresh instance for each test (bypassing singleton)
        service = DebugStateService::class.java.getDeclaredConstructor().newInstance()
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // Helper to create mock session
    private fun createMockSession(
        name: String = "TestSession",
        isSuspended: Boolean = true,
        currentFrame: XStackFrame? = null,
        suspendContext: XSuspendContext? = null
    ): XDebugSession {
        val session = mockk<XDebugSession>(relaxed = true)
        every { session.sessionName } returns name
        every { session.isSuspended } returns isSuspended
        every { session.currentStackFrame } returns currentFrame
        every { session.suspendContext } returns suspendContext
        return session
    }

    // Helper to create mock stack frame with source position
    private fun createMockStackFrame(
        fileName: String = "Test.java",
        filePath: String = "/src/Test.java",
        line: Int = 10
    ): XStackFrame {
        val virtualFile = mockk<VirtualFile>(relaxed = true)
        every { virtualFile.name } returns fileName
        every { virtualFile.path } returns filePath

        val sourcePosition = mockk<XSourcePosition>(relaxed = true)
        every { sourcePosition.file } returns virtualFile
        every { sourcePosition.line } returns line - 1 // 0-based internally

        val frame = mockk<XStackFrame>(relaxed = true)
        every { frame.sourcePosition } returns sourcePosition

        return frame
    }

    @Nested
    @DisplayName("Initial State")
    inner class InitialStateTest {

        @Test
        fun `should have NO_SESSION status initially`() {
            val state = service.getState()

            assertThat(state.status).isEqualTo(DebugStatus.NO_SESSION)
            assertThat(state.sessionName).isNull()
            assertThat(state.location).isNull()
            assertThat(state.stackTrace).isEmpty()
        }

        @Test
        fun `should return null for active session initially`() {
            assertThat(service.getActiveSession()).isNull()
        }

        @Test
        fun `should not be suspended initially`() {
            assertThat(service.isSuspended()).isFalse()
        }
    }

    @Nested
    @DisplayName("Session Lifecycle")
    inner class SessionLifecycleTest {

        @Test
        fun `onSessionStarted should set RUNNING status`() {
            val session = createMockSession(name = "MyApp", isSuspended = false)

            service.onSessionStarted(session)
            val state = service.getState()

            assertThat(state.status).isEqualTo(DebugStatus.RUNNING)
            assertThat(state.sessionName).isEqualTo("MyApp")
        }

        @Test
        fun `onSessionStarted should set active session`() {
            val session = createMockSession(name = "TestApp")

            service.onSessionStarted(session)

            assertThat(service.getActiveSession()).isEqualTo(session)
        }

        @Test
        fun `onSessionPaused should set SUSPENDED status`() {
            val frame = createMockStackFrame("Handler.java", "/src/Handler.java", 42)
            val session = createMockSession(name = "DebugApp", isSuspended = true, currentFrame = frame)

            service.onSessionStarted(session)
            service.onSessionPaused(session)
            val state = service.getState()

            assertThat(state.status).isEqualTo(DebugStatus.SUSPENDED)
            assertThat(service.isSuspended()).isTrue()
        }

        @Test
        fun `onSessionPaused should extract location from current frame`() {
            val frame = createMockStackFrame("Service.java", "/src/main/Service.java", 100)
            val session = createMockSession(name = "App", currentFrame = frame)

            service.onSessionStarted(session)
            service.onSessionPaused(session)
            val state = service.getState()

            assertThat(state.location).isNotNull
            assertThat(state.location?.file).isEqualTo("Service.java")
            assertThat(state.location?.filePath).isEqualTo("/src/main/Service.java")
            assertThat(state.location?.line).isEqualTo(100)
        }

        @Test
        fun `onSessionResumed should set RUNNING status`() {
            val frame = createMockStackFrame()
            val session = createMockSession(currentFrame = frame)

            service.onSessionStarted(session)
            service.onSessionPaused(session)
            service.onSessionResumed(session)
            val state = service.getState()

            assertThat(state.status).isEqualTo(DebugStatus.RUNNING)
            assertThat(service.isSuspended()).isFalse()
        }

        @Test
        fun `onSessionStopped should set NO_SESSION when last session`() {
            val session = createMockSession()

            service.onSessionStarted(session)
            service.onSessionStopped(session)
            val state = service.getState()

            assertThat(state.status).isEqualTo(DebugStatus.NO_SESSION)
            assertThat(state.sessionName).isNull()
            assertThat(service.getActiveSession()).isNull()
        }
    }

    @Nested
    @DisplayName("Multiple Sessions")
    inner class MultipleSessionsTest {

        @Test
        fun `should track multiple sessions`() {
            val session1 = createMockSession(name = "App1")
            val session2 = createMockSession(name = "App2")

            service.onSessionStarted(session1)
            service.onSessionStarted(session2)

            // Latest session becomes active
            assertThat(service.getActiveSession()).isEqualTo(session2)
            assertThat(service.getState().sessionName).isEqualTo("App2")
        }

        @Test
        fun `stopping one session should keep other sessions active`() {
            val frame1 = createMockStackFrame("App1.java", "/App1.java", 10)
            val frame2 = createMockStackFrame("App2.java", "/App2.java", 20)
            val session1 = createMockSession(name = "App1", currentFrame = frame1)
            val session2 = createMockSession(name = "App2", currentFrame = frame2)

            service.onSessionStarted(session1)
            service.onSessionStarted(session2)
            service.onSessionStopped(session2)

            assertThat(service.getState().status).isNotEqualTo(DebugStatus.NO_SESSION)
            assertThat(service.getActiveSession()).isEqualTo(session1)
        }

        @Test
        fun `pausing session should make it active`() {
            val frame1 = createMockStackFrame("First.java", "/First.java", 5)
            val frame2 = createMockStackFrame("Second.java", "/Second.java", 15)
            val session1 = createMockSession(name = "First", currentFrame = frame1)
            val session2 = createMockSession(name = "Second", currentFrame = frame2)

            service.onSessionStarted(session1)
            service.onSessionStarted(session2)
            service.onSessionPaused(session1) // Pause first session

            assertThat(service.getActiveSession()).isEqualTo(session1)
            assertThat(service.getState().sessionName).isEqualTo("First")
        }
    }

    @Nested
    @DisplayName("Location Extraction")
    inner class LocationExtractionTest {

        @Test
        fun `should extract location when frame has source position`() {
            val frame = createMockStackFrame(
                fileName = "Calculator.java",
                filePath = "/project/src/main/java/Calculator.java",
                line = 55
            )
            val session = createMockSession(currentFrame = frame)

            service.onSessionStarted(session)
            service.onSessionPaused(session)
            val location = service.getState().location

            assertThat(location).isNotNull
            assertThat(location?.file).isEqualTo("Calculator.java")
            assertThat(location?.filePath).isEqualTo("/project/src/main/java/Calculator.java")
            assertThat(location?.line).isEqualTo(55)
        }

        @Test
        fun `should return null location when no current frame`() {
            val session = createMockSession(currentFrame = null)

            service.onSessionStarted(session)
            service.onSessionPaused(session)

            assertThat(service.getState().location).isNull()
        }

        @Test
        fun `should return null location when frame has no source position`() {
            val frame = mockk<XStackFrame>(relaxed = true)
            every { frame.sourcePosition } returns null
            val session = createMockSession(currentFrame = frame)

            service.onSessionStarted(session)
            service.onSessionPaused(session)

            assertThat(service.getState().location).isNull()
        }
    }

    @Nested
    @DisplayName("Lazy Stack Trace Loading")
    inner class LazyStackTraceTest {

        @Test
        fun `getState should return empty stack trace by default`() {
            val frame = createMockStackFrame()
            val session = createMockSession(currentFrame = frame)

            service.onSessionStarted(session)
            service.onSessionPaused(session)

            // Fast method should not compute stack trace
            val state = service.getState()
            assertThat(state.stackTrace).isEmpty()
        }

        @Test
        fun `getStateWithStackTrace should return cached state when no active session`() {
            val state = service.getStateWithStackTrace()

            assertThat(state.status).isEqualTo(DebugStatus.NO_SESSION)
            assertThat(state.stackTrace).isEmpty()
        }
    }

    @Nested
    @DisplayName("Cache Invalidation")
    inner class CacheInvalidationTest {

        @Test
        fun `state version should increment on session events`() {
            val frame = createMockStackFrame()
            val session = createMockSession(currentFrame = frame)

            // Each state change should invalidate the cache
            service.onSessionStarted(session)
            val state1 = service.getState()

            service.onSessionPaused(session)
            val state2 = service.getState()

            service.onSessionResumed(session)
            val state3 = service.getState()

            // States should reflect different statuses
            assertThat(state1.status).isEqualTo(DebugStatus.RUNNING)
            assertThat(state2.status).isEqualTo(DebugStatus.SUSPENDED)
            assertThat(state3.status).isEqualTo(DebugStatus.RUNNING)
        }

        @Test
        fun `stopping session should clear cached state`() {
            val frame = createMockStackFrame()
            val session = createMockSession(currentFrame = frame)

            service.onSessionStarted(session)
            service.onSessionPaused(session)

            // Verify we have state
            assertThat(service.getState().status).isEqualTo(DebugStatus.SUSPENDED)

            service.onSessionStopped(session)

            // After stop, should be back to initial state
            val finalState = service.getState()
            assertThat(finalState.status).isEqualTo(DebugStatus.NO_SESSION)
            assertThat(finalState.location).isNull()
        }
    }

    @Nested
    @DisplayName("isSuspended")
    inner class IsSuspendedTest {

        @Test
        fun `should return true when status is SUSPENDED`() {
            val frame = createMockStackFrame()
            val session = createMockSession(currentFrame = frame)

            service.onSessionStarted(session)
            service.onSessionPaused(session)

            assertThat(service.isSuspended()).isTrue()
        }

        @Test
        fun `should return false when status is RUNNING`() {
            val session = createMockSession()

            service.onSessionStarted(session)

            assertThat(service.isSuspended()).isFalse()
        }

        @Test
        fun `should return false when status is NO_SESSION`() {
            assertThat(service.isSuspended()).isFalse()
        }

        @Test
        fun `should return false after resume`() {
            val frame = createMockStackFrame()
            val session = createMockSession(currentFrame = frame)

            service.onSessionStarted(session)
            service.onSessionPaused(session)
            assertThat(service.isSuspended()).isTrue()

            service.onSessionResumed(session)
            assertThat(service.isSuspended()).isFalse()
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCasesTest {

        @Test
        fun `should handle null session name gracefully`() {
            val session = mockk<XDebugSession>(relaxed = true)
            every { session.sessionName } returns "UntitledSession"
            every { session.isSuspended } returns false
            every { session.currentStackFrame } returns null

            service.onSessionStarted(session)

            assertThat(service.getState().sessionName).isEqualTo("UntitledSession")
        }

        @Test
        fun `should handle rapid state changes`() {
            val frame = createMockStackFrame()
            val session = createMockSession(currentFrame = frame)

            // Simulate rapid state changes
            repeat(10) {
                service.onSessionStarted(session)
                service.onSessionPaused(session)
                service.onSessionResumed(session)
            }
            service.onSessionStopped(session)

            // Should end up in NO_SESSION state
            assertThat(service.getState().status).isEqualTo(DebugStatus.NO_SESSION)
        }

        @Test
        fun `should handle stopping session that was never started`() {
            val session = createMockSession(name = "NeverStarted")

            // This shouldn't throw
            service.onSessionStopped(session)

            assertThat(service.getState().status).isEqualTo(DebugStatus.NO_SESSION)
        }
    }
}
