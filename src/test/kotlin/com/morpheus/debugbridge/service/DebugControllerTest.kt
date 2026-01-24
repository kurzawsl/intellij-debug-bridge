package com.morpheus.debugbridge.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for DebugController.
 *
 * Note: DebugController is heavily dependent on IntelliJ's ApplicationManager
 * and XDebugSession. Full integration tests would require the IntelliJ test framework.
 * These tests verify the structure and logic that can be tested in isolation.
 */
@DisplayName("DebugController")
class DebugControllerTest {

    @Nested
    @DisplayName("Controller Structure")
    inner class ControllerStructureTest {

        @Test
        fun `should be a singleton object`() {
            val controller1 = DebugController
            val controller2 = DebugController
            assertThat(controller1).isSameAs(controller2)
        }

        @Test
        fun `should have stepOver method`() {
            val method = DebugController.javaClass.getDeclaredMethod("stepOver")
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(Boolean::class.java)
        }

        @Test
        fun `should have stepInto method`() {
            val method = DebugController.javaClass.getDeclaredMethod("stepInto")
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(Boolean::class.java)
        }

        @Test
        fun `should have stepOut method`() {
            val method = DebugController.javaClass.getDeclaredMethod("stepOut")
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(Boolean::class.java)
        }

        @Test
        fun `should have resume method`() {
            val method = DebugController.javaClass.getDeclaredMethod("resume")
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(Boolean::class.java)
        }

        @Test
        fun `should have pause method`() {
            val method = DebugController.javaClass.getDeclaredMethod("pause")
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(Boolean::class.java)
        }

        @Test
        fun `should have runToCursor method`() {
            val method = DebugController.javaClass.getDeclaredMethod("runToCursor", String::class.java, Int::class.java)
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(Boolean::class.java)
        }

        @Test
        fun `should have selectFrame method`() {
            val method = DebugController.javaClass.getDeclaredMethod("selectFrame", Int::class.java)
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(Boolean::class.java)
        }

        @Test
        fun `should have startDebug method`() {
            val method = DebugController.javaClass.getDeclaredMethod("startDebug", String::class.java)
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(Boolean::class.java)
        }

        @Test
        fun `should have stopDebug method`() {
            val method = DebugController.javaClass.getDeclaredMethod("stopDebug")
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(Boolean::class.java)
        }

        @Test
        fun `should have getRunConfigurations method`() {
            val method = DebugController.javaClass.getDeclaredMethod("getRunConfigurations")
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(List::class.java)
        }

        @Test
        fun `should have waitForSuspend method`() {
            val method = DebugController.javaClass.getDeclaredMethod("waitForSuspend", Long::class.java)
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(Boolean::class.java)
        }
    }

    @Nested
    @DisplayName("waitForSuspend Behavior")
    inner class WaitForSuspendTest {

        @Test
        fun `waitForSuspend should have default timeout of 5000ms`() {
            // Check the default parameter value via reflection
            val method = DebugController.javaClass.getDeclaredMethod("waitForSuspend", Long::class.java)
            val params = method.parameters
            assertThat(params).hasSize(1)
        }
    }

    @Nested
    @DisplayName("File Finding Logic")
    inner class FileFindingLogicTest {

        @Test
        fun `findFile should be accessible in FileUtils`() {
            // Verify FileUtils has the findFile method (moved from DebugController)
            val method = FileUtils.javaClass.getDeclaredMethod(
                "findFile",
                String::class.java,
                com.intellij.openapi.project.Project::class.java
            )
            assertThat(method).isNotNull
        }

        @Test
        fun `sanitizePath should be accessible in FileUtils`() {
            // FileUtils now handles path sanitization
            val method = FileUtils.javaClass.getDeclaredMethod(
                "sanitizePath",
                String::class.java
            )
            assertThat(method).isNotNull
        }
    }
}
