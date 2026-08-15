package com.cosmos.unreddit.data.remote.api.reddit

import com.cosmos.unreddit.data.remote.api.reddit.RedditRateLimitInterceptor.Companion.parseRetryAfter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class RetryAfterTest {

    @Test
    fun `reads a delay in seconds`() {
        assertEquals(TimeUnit.SECONDS.toMillis(30), parseRetryAfter("30", NOW))
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        assertEquals(TimeUnit.SECONDS.toMillis(5), parseRetryAfter("  5 ", NOW))
    }

    @Test
    fun `a delay of zero is a figure, not a missing one`() {
        assertEquals(0L, parseRetryAfter("0", NOW))
    }

    @Test
    fun `a negative delay is floored at zero`() {
        assertEquals(0L, parseRetryAfter("-10", NOW))
    }

    @Test
    fun `reads an HTTP-date as the wait until then`() {
        // 60s after NOW
        val header = "Thu, 01 Jan 1970 00:02:00 GMT"

        assertEquals(TimeUnit.SECONDS.toMillis(60), parseRetryAfter(header, NOW))
    }

    @Test
    fun `an HTTP-date already past means no wait rather than a negative one`() {
        val header = "Thu, 01 Jan 1970 00:00:10 GMT"

        assertEquals(0L, parseRetryAfter(header, NOW))
    }

    @Test
    fun `an absent header gives no figure`() {
        assertNull(parseRetryAfter(null, NOW))
    }

    @Test
    fun `a blank header gives no figure`() {
        assertNull(parseRetryAfter("   ", NOW))
    }

    @Test
    fun `an unparseable header gives no figure rather than zero`() {
        // Zero would be read as "retry immediately", which is the opposite of what a 429 means
        assertNull(parseRetryAfter("soon", NOW))
    }

    companion object {
        /** 60s into the epoch, so the date cases can be written as readable literals */
        private val NOW = TimeUnit.SECONDS.toMillis(60)
    }
}
