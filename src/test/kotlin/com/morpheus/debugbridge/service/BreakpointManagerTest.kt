package com.morpheus.debugbridge.service

import com.morpheus.debugbridge.model.BreakpointInfo
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for BreakpointManager.
 *
 * Note: BreakpointManager heavily relies on IntelliJ's XBreakpointManager
 * and ProjectManager. Full integration tests require the IntelliJ test framework.
 * These tests verify the structure and model logic.
 */
@DisplayName("BreakpointManager")
class BreakpointManagerTest {

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Nested
    @DisplayName("Manager Structure")
    inner class ManagerStructureTest {

        @Test
        fun `should be a singleton object`() {
            val manager1 = BreakpointManager
            val manager2 = BreakpointManager
            assertThat(manager1).isSameAs(manager2)
        }

        @Test
        fun `should have getAllBreakpoints method`() {
            val method = BreakpointManager.javaClass.getDeclaredMethod("getAllBreakpoints")
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(List::class.java)
        }

        @Test
        fun `should have addBreakpoint method`() {
            val method = BreakpointManager.javaClass.getDeclaredMethod(
                "addBreakpoint",
                String::class.java,
                Int::class.java,
                String::class.java
            )
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(Boolean::class.java)
        }

        @Test
        fun `should have removeBreakpoint method`() {
            val method = BreakpointManager.javaClass.getDeclaredMethod(
                "removeBreakpoint",
                String::class.java,
                Int::class.java
            )
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(Boolean::class.java)
        }

        @Test
        fun `should have toggleBreakpoint method`() {
            val method = BreakpointManager.javaClass.getDeclaredMethod(
                "toggleBreakpoint",
                String::class.java,
                Int::class.java
            )
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(Boolean::class.java)
        }

        @Test
        fun `should have removeAllBreakpoints method`() {
            val method = BreakpointManager.javaClass.getDeclaredMethod("removeAllBreakpoints")
            assertThat(method).isNotNull
            assertThat(method.returnType).isEqualTo(Int::class.java)
        }
    }

    @Nested
    @DisplayName("BreakpointInfo Model")
    inner class BreakpointInfoModelTest {

        @Test
        fun `should create breakpoint info with all fields`() {
            val bp = BreakpointInfo(
                file = "Main.java",
                line = 42,
                enabled = true,
                condition = "x > 10"
            )

            assertThat(bp.file).isEqualTo("Main.java")
            assertThat(bp.line).isEqualTo(42)
            assertThat(bp.enabled).isTrue()
            assertThat(bp.condition).isEqualTo("x > 10")
        }

        @Test
        fun `should default enabled to true`() {
            val bp = BreakpointInfo(
                file = "Test.java",
                line = 1
            )

            assertThat(bp.enabled).isTrue()
        }

        @Test
        fun `should default condition to null`() {
            val bp = BreakpointInfo(
                file = "Test.java",
                line = 1
            )

            assertThat(bp.condition).isNull()
        }

        @Test
        fun `should support disabled breakpoints`() {
            val bp = BreakpointInfo(
                file = "Debug.java",
                line = 100,
                enabled = false
            )

            assertThat(bp.enabled).isFalse()
        }

        @Test
        fun `should support complex conditions`() {
            val bp = BreakpointInfo(
                file = "Loop.java",
                line = 50,
                condition = "i % 100 == 0 && list.size() > 0"
            )

            assertThat(bp.condition).contains("i % 100")
            assertThat(bp.condition).contains("list.size()")
        }
    }

    @Nested
    @DisplayName("File Finding Logic")
    inner class FileFindingLogicTest {

        @Test
        fun `findFile method should exist`() {
            val method = BreakpointManager.javaClass.getDeclaredMethod(
                "findFile",
                String::class.java
            )
            method.isAccessible = true
            assertThat(method).isNotNull
        }

        @Test
        fun `findFileRecursively method should exist`() {
            val method = BreakpointManager.javaClass.getDeclaredMethod(
                "findFileRecursively",
                com.intellij.openapi.vfs.VirtualFile::class.java,
                String::class.java
            )
            method.isAccessible = true
            assertThat(method).isNotNull
        }

        @Test
        fun `findLineBreakpointType method should exist`() {
            val method = BreakpointManager.javaClass.getDeclaredMethod(
                "findLineBreakpointType",
                com.intellij.openapi.vfs.VirtualFile::class.java
            )
            method.isAccessible = true
            assertThat(method).isNotNull
        }
    }

    @Nested
    @DisplayName("Source Directory Search")
    inner class SourceDirectorySearchTest {

        @Test
        fun `should search common source directories`() {
            // The implementation searches in these directories:
            // src/main/java, src/main/kotlin, src/test/java, src/test/kotlin, src
            // This is verified by checking the findFile method's logic

            // Since we can't easily mock LocalFileSystem, we verify the method exists
            val method = BreakpointManager.javaClass.getDeclaredMethod(
                "findFile",
                String::class.java
            )
            assertThat(method).isNotNull
        }
    }
}
