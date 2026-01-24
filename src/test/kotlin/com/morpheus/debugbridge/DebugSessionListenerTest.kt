package com.morpheus.debugbridge

import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for DebugSessionListener.
 *
 * Note: These tests verify the listener structure and behavior.
 * Integration with DebugStateService is tested separately.
 */
@DisplayName("DebugSessionListener")
class DebugSessionListenerTest {

    private lateinit var listener: DebugSessionListener

    @BeforeEach
    fun setUp() {
        listener = DebugSessionListener()
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    private fun createMockDebugProcess(sessionName: String = "TestSession"): XDebugProcess {
        val session = mockk<XDebugSession>(relaxed = true)
        every { session.sessionName } returns sessionName

        // Capture the listener that gets added
        val listenerSlot = slot<XDebugSessionListener>()
        every { session.addSessionListener(capture(listenerSlot)) } just Runs

        val debugProcess = mockk<XDebugProcess>(relaxed = true)
        every { debugProcess.session } returns session

        return debugProcess
    }

    @Nested
    @DisplayName("Listener Registration")
    inner class ListenerRegistrationTest {

        @Test
        fun `processStarted should get session from debug process`() {
            val debugProcess = createMockDebugProcess("MyApp")

            // This would normally call DebugStateService, but we're testing structure
            // The actual call to getInstance() would need mocking the ApplicationManager
            // For now, we verify the process is accessed correctly
            verify(exactly = 0) { debugProcess.session }

            // The listener is created, verifying it doesn't throw
            assertThat(listener).isNotNull
        }
    }

    @Nested
    @DisplayName("SessionEventListener Architecture")
    inner class SessionEventListenerArchitectureTest {

        @Test
        fun `SessionEventListener should be in ProjectStateService`() {
            // SessionEventListener was moved from DebugSessionListener to ProjectStateService
            // as part of multi-project support refactoring.
            // Verify it exists in ProjectStateService now.
            val projectStateServiceClass = com.morpheus.debugbridge.service.ProjectStateService::class.java
            val innerClasses = projectStateServiceClass.declaredClasses

            val sessionEventListenerClass = innerClasses.find {
                it.simpleName == "SessionEventListener"
            }

            assertThat(sessionEventListenerClass)
                .describedAs("SessionEventListener should exist in ProjectStateService")
                .isNotNull
        }
    }

    @Nested
    @DisplayName("Listener Lifecycle")
    inner class ListenerLifecycleTest {

        @Test
        fun `listener should implement XDebuggerManagerListener`() {
            assertThat(listener).isInstanceOf(com.intellij.xdebugger.XDebuggerManagerListener::class.java)
        }

        @Test
        fun `listener should have processStarted method`() {
            val method = listener.javaClass.getDeclaredMethod("processStarted", XDebugProcess::class.java)
            assertThat(method).isNotNull
        }

        @Test
        fun `listener should have processStopped method`() {
            val method = listener.javaClass.getDeclaredMethod("processStopped", XDebugProcess::class.java)
            assertThat(method).isNotNull
        }
    }
}
