package com.morpheus.debugbridge.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for RateLimiter.
 */
@DisplayName("RateLimiter")
class RateLimiterTest {

    @BeforeEach
    fun setUp() {
        RateLimiter.reset()
    }

    @Nested
    @DisplayName("Basic Rate Limiting")
    inner class BasicRateLimitingTest {

        @Test
        fun `should allow requests under the limit`() {
            // Read limit is 100/sec
            repeat(50) {
                assertThat(RateLimiter.isAllowed("read")).isTrue()
            }
        }

        @Test
        fun `should block requests over the limit`() {
            // Control limit is 10/sec
            repeat(10) {
                assertThat(RateLimiter.isAllowed("control")).isTrue()
            }
            // 11th request should be blocked
            assertThat(RateLimiter.isAllowed("control")).isFalse()
        }

        @Test
        fun `should track different categories separately`() {
            // Exhaust control limit
            repeat(10) {
                assertThat(RateLimiter.isAllowed("control")).isTrue()
            }
            assertThat(RateLimiter.isAllowed("control")).isFalse()

            // Read category should still work
            assertThat(RateLimiter.isAllowed("read")).isTrue()
        }
    }

    @Nested
    @DisplayName("Status Reporting")
    inner class StatusReportingTest {

        @Test
        fun `should report current status correctly`() {
            repeat(5) {
                RateLimiter.isAllowed("control")
            }

            val (count, max) = RateLimiter.getStatus("control")
            assertThat(count).isEqualTo(5)
            assertThat(max).isEqualTo(10)
        }

        @Test
        fun `should return zero count for unused category`() {
            val (count, max) = RateLimiter.getStatus("read")
            assertThat(count).isEqualTo(0)
            assertThat(max).isEqualTo(100)
        }

        @Test
        fun `should use default limit for unknown category`() {
            val (_, max) = RateLimiter.getStatus("unknown")
            assertThat(max).isEqualTo(50) // default limit
        }
    }

    @Nested
    @DisplayName("Reset Functionality")
    inner class ResetFunctionalityTest {

        @Test
        fun `should clear state on reset`() {
            // Exhaust the limit
            repeat(10) {
                RateLimiter.isAllowed("control")
            }
            assertThat(RateLimiter.isAllowed("control")).isFalse()

            // Reset
            RateLimiter.reset()

            // Should allow again
            assertThat(RateLimiter.isAllowed("control")).isTrue()
        }
    }

    @Nested
    @DisplayName("Category Limits")
    inner class CategoryLimitsTest {

        @Test
        fun `read category should have high limit`() {
            val (_, max) = RateLimiter.getStatus("read")
            assertThat(max).isEqualTo(100)
        }

        @Test
        fun `control category should have lower limit`() {
            val (_, max) = RateLimiter.getStatus("control")
            assertThat(max).isEqualTo(10)
        }

        @Test
        fun `evaluate category should have moderate limit`() {
            val (_, max) = RateLimiter.getStatus("evaluate")
            assertThat(max).isEqualTo(20)
        }
    }
}
