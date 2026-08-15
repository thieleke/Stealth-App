package com.cosmos.unreddit.data.remote.api.reddit

import okhttp3.Interceptor
import okhttp3.Response
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Handles Reddit's 429 responses: records the requested back-off in [rateLimiter] so the rest of
 * the app stops competing for a budget that is already spent, and absorbs the wait inline when it
 * is short enough to be worth retrying here.
 */
class RedditRateLimitInterceptor(
    private val rateLimiter: RedditRateLimiter
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        if (response.code != HTTP_TOO_MANY_REQUESTS) {
            return response
        }

        val retryAfter = parseRetryAfter(response.header(RETRY_AFTER), System.currentTimeMillis())
        rateLimiter.onRateLimited(retryAfter)

        // A short wait is worth absorbing here: the caller gets its data instead of an error. A
        // longer one belongs to the rate limiter, which can hold the whole app back without
        // keeping a request thread parked for it
        if (retryAfter == null || retryAfter > MAX_INLINE_RETRY_MS) {
            return response
        }

        response.close()
        Thread.sleep(retryAfter)

        val retried = chain.proceed(chain.request())

        if (retried.code == HTTP_TOO_MANY_REQUESTS) {
            rateLimiter.onRateLimited(
                parseRetryAfter(retried.header(RETRY_AFTER), System.currentTimeMillis())
            )
        }

        return retried
    }

    companion object {
        private const val HTTP_TOO_MANY_REQUESTS = 429

        private const val RETRY_AFTER = "Retry-After"

        private val MAX_INLINE_RETRY_MS = TimeUnit.SECONDS.toMillis(5)

        /** The format RFC 7231 requires for an HTTP-date. */
        private const val HTTP_DATE_PATTERN = "EEE, dd MMM yyyy HH:mm:ss zzz"

        /**
         * Reads a `Retry-After` value, which is either a delay in seconds or an HTTP-date
         * (RFC 7231 §7.1.3), into milliseconds to wait from [nowMillis].
         *
         * Returns null when the header is absent or in neither form — read by callers as "no
         * figure given", which is different from a figure of zero.
         */
        internal fun parseRetryAfter(value: String?, nowMillis: Long): Long? {
            val header = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null

            header.toLongOrNull()?.let { seconds ->
                return TimeUnit.SECONDS.toMillis(seconds).coerceAtLeast(0L)
            }

            val date = try {
                SimpleDateFormat(HTTP_DATE_PATTERN, Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("GMT") }
                    .parse(header)
            } catch (e: ParseException) {
                null
            } ?: return null

            // A date already in the past means the wait is over, not that there was none
            return (date.time - nowMillis).coerceAtLeast(0L)
        }
    }
}
