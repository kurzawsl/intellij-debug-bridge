package com.morpheus.debugbridge.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Simple sliding window rate limiter for REST API endpoints.
 *
 * SECURITY: Prevents resource exhaustion attacks even on localhost.
 * A malicious or buggy MCP client could flood the API with requests.
 *
 * Thread-safe implementation using ConcurrentHashMap and AtomicLong.
 */
object RateLimiter {

    /**
     * Rate limit configuration per endpoint category.
     * Values are: (requests per window, window size in ms)
     */
    private val RATE_LIMITS = mapOf(
        // Read operations - higher limits
        "read" to RateLimit(100, 1000),      // 100 req/sec
        // Control operations - lower limits to prevent accidental rapid stepping
        "control" to RateLimit(10, 1000),     // 10 req/sec
        // Evaluate operations - limited to prevent runaway evaluation
        "evaluate" to RateLimit(20, 1000),    // 20 req/sec
        // Default fallback
        "default" to RateLimit(50, 1000)      // 50 req/sec
    )

    private data class RateLimit(val maxRequests: Int, val windowMs: Long)

    private data class WindowState(
        val count: AtomicLong = AtomicLong(0),
        val windowStart: AtomicLong = AtomicLong(System.currentTimeMillis())
    )

    // Track rate per endpoint category
    private val windowStates = ConcurrentHashMap<String, WindowState>()

    /**
     * Check if request is allowed under rate limit.
     *
     * @param category Rate limit category (read, control, evaluate)
     * @return true if request is allowed, false if rate limited
     */
    fun isAllowed(category: String): Boolean {
        val limit = RATE_LIMITS[category] ?: RATE_LIMITS["default"]!!
        val state = windowStates.computeIfAbsent(category) { WindowState() }

        val now = System.currentTimeMillis()
        val windowStart = state.windowStart.get()

        // Check if we need to start a new window
        if (now - windowStart >= limit.windowMs) {
            // Try to reset the window atomically
            if (state.windowStart.compareAndSet(windowStart, now)) {
                state.count.set(1)
                return true
            }
            // Another thread reset it, retry
            return isAllowed(category)
        }

        // Increment and check
        val currentCount = state.count.incrementAndGet()
        return currentCount <= limit.maxRequests
    }

    /**
     * Get current rate limit status for monitoring.
     *
     * @param category Rate limit category
     * @return Pair of (current count, max allowed)
     */
    fun getStatus(category: String): Pair<Long, Int> {
        val limit = RATE_LIMITS[category] ?: RATE_LIMITS["default"]!!
        val state = windowStates[category] ?: return Pair(0L, limit.maxRequests)
        return Pair(state.count.get(), limit.maxRequests)
    }

    /**
     * Reset rate limiter state (for testing).
     */
    fun reset() {
        windowStates.clear()
    }
}
