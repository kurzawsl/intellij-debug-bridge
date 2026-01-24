package com.morpheus.debugbridge.config

/**
 * Centralized timeout configuration for the Debug Bridge plugin.
 *
 * All timeouts are documented with their purpose and rationale.
 * This avoids magic numbers scattered throughout the codebase and
 * makes it easy to tune performance characteristics.
 *
 * DESIGN RATIONALE:
 * - Short timeouts for interactive operations (UI-blocking)
 * - Medium timeouts for background async operations
 * - Longer timeouts for potentially slow operations (expression evaluation)
 */
object TimeoutConfig {

    // ========== Variable Extraction Timeouts ==========

    /**
     * Timeout for collecting variables from a stack frame.
     *
     * This is an async callback operation. If the debugger is slow or
     * there are many variables, this may take time. However, users
     * expect quick response, so we cap it.
     *
     * RATIONALE: 1500ms is enough for most frames (typically <100 vars)
     * but won't leave users waiting too long.
     */
    const val VARIABLE_COLLECT_MS = 1500L

    /**
     * Timeout for extracting variable presentation (type + value).
     *
     * Presentation extraction involves async rendering which can be slow
     * for complex objects. We use parallel extraction to speed this up.
     *
     * RATIONALE: 2000ms allows complex objects while keeping responsiveness.
     */
    const val VARIABLE_PRESENTATION_MS = 2000L

    /**
     * Timeout for a single variable's presentation extraction.
     *
     * Individual variables should be quick; this prevents one slow
     * variable from blocking the entire response.
     */
    const val SINGLE_VARIABLE_MS = 2000L

    // ========== Stack Trace Timeouts ==========

    /**
     * Timeout for stack trace computation.
     *
     * Stack traces are extracted lazily and cached. Most debug
     * sessions have shallow stacks (<50 frames).
     *
     * RATIONALE: 500ms is usually sufficient; if exceeded, we
     * return the current frame and let the full trace load async.
     */
    const val STACK_TRACE_MS = 500L

    // ========== Debug Action Timeouts ==========

    /**
     * Timeout for debug actions (step, resume, pause).
     *
     * These actions invoke IntelliJ APIs on the EDT. They should
     * complete quickly but may block on slow debugger operations.
     */
    const val DEBUG_ACTION_MS = 5000L

    /**
     * Timeout for run-to-cursor operation.
     *
     * This waits for execution to reach a specific line, which
     * may take variable time depending on code path.
     */
    const val RUN_TO_CURSOR_MS = 5000L

    /**
     * Maximum time to wait for debugger to suspend after an action.
     *
     * After step/run-to-cursor, we poll for suspended state.
     */
    const val WAIT_FOR_SUSPEND_MS = 5000L

    // ========== Expression Evaluation Timeouts ==========

    /**
     * Timeout for expression evaluation.
     *
     * Expression evaluation can be slow for complex expressions or
     * those that trigger method calls in the target JVM.
     *
     * RATIONALE: 10s allows complex evaluations while preventing
     * infinite hangs from expressions with side effects.
     */
    const val EXPRESSION_EVAL_MS = 10000L

    // ========== Breakpoint Timeouts ==========

    /**
     * Timeout for breakpoint operations (add, remove, toggle).
     *
     * These involve file system lookups and IntelliJ's breakpoint manager.
     */
    const val BREAKPOINT_OPERATION_MS = 5000L

    // ========== Frame Operations ==========

    /**
     * Timeout for frame selection and computation.
     */
    const val FRAME_OPERATION_MS = 3000L

    /**
     * Timeout for expanding nested variables.
     */
    const val VARIABLE_EXPAND_MS = 3000L

    // ========== Retry Configuration ==========

    /**
     * Initial poll interval for wait loops (exponential backoff).
     * Starts fast to catch quick operations.
     */
    const val POLL_INITIAL_MS = 10L

    /**
     * Maximum poll interval for wait loops.
     * Caps backoff to prevent excessive delays.
     */
    const val POLL_MAX_MS = 100L

    // ========== Server Configuration ==========

    /**
     * Default REST server port.
     */
    const val DEFAULT_PORT = 19999

    /**
     * Server stop grace period.
     */
    const val SERVER_STOP_GRACE_MS = 1000L

    /**
     * Server stop timeout.
     */
    const val SERVER_STOP_TIMEOUT_MS = 2000L
}
