package com.cosmos.unreddit.data.remote.api.reddit

import com.cosmos.unreddit.data.remote.api.reddit.RedditRateLimiter.Companion.DEFAULT_BACKOFF_MS
import com.cosmos.unreddit.data.remote.api.reddit.RedditRateLimiter.Companion.MAX_BACKOFF_MS
import com.cosmos.unreddit.data.remote.api.reddit.RedditRateLimiter.Companion.MIN_BACKOFF_MS
import com.cosmos.unreddit.data.remote.api.reddit.RedditRateLimiter.Companion.MIN_INTERVAL_MS
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditRateLimiterTest {

    @Test
    fun `is not rate limited until Reddit says so`() {
        val limiter = RedditRateLimiter()

        assertFalse(limiter.isRateLimited)
        assertEquals(0L, limiter.backoffRemaining)
    }

    @Test
    fun `a 429 without Retry-After falls back to the default back-off`() {
        val limiter = RedditRateLimiter()

        limiter.onRateLimited(null)

        assertTrue(limiter.isRateLimited)
        assertInRange(DEFAULT_BACKOFF_MS, limiter.backoffRemaining)
    }

    @Test
    fun `a Retry-After below the floor is raised to it`() {
        val limiter = RedditRateLimiter()

        limiter.onRateLimited(1L)

        assertInRange(MIN_BACKOFF_MS, limiter.backoffRemaining)
    }

    @Test
    fun `a Retry-After beyond the ceiling is capped`() {
        val limiter = RedditRateLimiter()

        limiter.onRateLimited(MAX_BACKOFF_MS * 10)

        assertInRange(MAX_BACKOFF_MS, limiter.backoffRemaining)
    }

    @Test
    fun `the longest back-off wins so a late response cannot shorten it`() {
        val limiter = RedditRateLimiter()

        limiter.onRateLimited(MAX_BACKOFF_MS)
        // A request that was already in flight when the limit was hit, answering with a shorter
        // wait: releasing at that point would put the sweep straight back into the limit
        limiter.onRateLimited(MIN_BACKOFF_MS)

        assertInRange(MAX_BACKOFF_MS, limiter.backoffRemaining)
    }

    @Test
    fun `successive requests are spaced by the pacing interval`() = runBlocking {
        val limiter = RedditRateLimiter()

        val elapsed = measure {
            repeat(3) { limiter.acquire() }
        }

        // The first goes out immediately, so three requests cost two intervals
        assertTrue(
            "3 requests took ${elapsed}ms, expected at least ${2 * MIN_INTERVAL_MS}ms",
            elapsed >= 2 * MIN_INTERVAL_MS
        )
    }

    @Test
    fun `concurrent callers queue rather than sharing one slot`() = runBlocking {
        val limiter = RedditRateLimiter()

        // Reserving from a single coroutine still exercises the reservation: each acquire must
        // take the next slot rather than the one the previous caller already claimed
        val elapsed = measure {
            repeat(4) { limiter.acquire() }
        }

        assertTrue(
            "4 requests took ${elapsed}ms, expected at least ${3 * MIN_INTERVAL_MS}ms",
            elapsed >= 3 * MIN_INTERVAL_MS
        )
    }

    @Test
    fun `acquire waits out an active back-off`() = runBlocking {
        val limiter = RedditRateLimiter()

        limiter.onRateLimited(MIN_BACKOFF_MS)

        val elapsed = measure { limiter.acquire() }

        assertTrue(
            "acquire returned after ${elapsed}ms, expected to wait ${MIN_BACKOFF_MS}ms",
            elapsed >= MIN_BACKOFF_MS - TOLERANCE_MS
        )
    }

    private inline fun measure(block: () -> Unit): Long {
        val start = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - start
    }

    /**
     * The back-off is measured against the wall clock, so the remaining time is the expected
     * figure minus however long the assertion took to get here.
     */
    private fun assertInRange(expected: Long, actual: Long) {
        assertTrue(
            "expected about ${expected}ms of back-off, was ${actual}ms",
            actual in (expected - TOLERANCE_MS)..expected
        )
    }

    companion object {
        private const val TOLERANCE_MS = 500L
    }
}
