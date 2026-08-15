package com.cosmos.unreddit.data.remote.api.reddit

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Paces outgoing Reddit requests and remembers the back-off Reddit asks for when it answers 429.
 *
 * Reddit rate limits unauthenticated clients, and the app can exhaust that budget on its own: the
 * saved Users timeline fetches one listing per saved user, so a profile with a few hundred saved
 * users fires a few hundred requests as fast as the network allows. Spacing them keeps that sweep
 * from taking down whichever screen the user is actually looking at — the failure is otherwise
 * reported far from its cause, since the sweep swallows its own errors.
 */
@Singleton
class RedditRateLimiter @Inject constructor() {

    private val mutex = Mutex()

    /**
     * Earliest time the next paced request may be sent. Guarded by [mutex] so that concurrent
     * callers reserve distinct slots rather than all reading the same one.
     */
    private var nextSlot = 0L

    private val backoffUntil = AtomicLong(0)

    /** Milliseconds left of the back-off Reddit asked for, or 0 when it is not throttling us. */
    val backoffRemaining: Long
        get() = max(0L, backoffUntil.get() - System.currentTimeMillis())

    val isRateLimited: Boolean
        get() = backoffRemaining > 0

    /**
     * Suspends until this caller's turn, honoring both the spacing between requests and any
     * back-off in effect.
     *
     * The slot is reserved while the lock is held and waited out afterwards, so callers released
     * from a back-off leave one at a time instead of stampeding the moment it expires.
     */
    suspend fun acquire() {
        val waitMillis = mutex.withLock {
            val now = System.currentTimeMillis()
            val slot = maxOf(now, nextSlot, backoffUntil.get())
            nextSlot = slot + MIN_INTERVAL_MS
            slot - now
        }

        if (waitMillis > 0) {
            delay(waitMillis)
        }
    }

    /**
     * Records the back-off from a 429. [retryAfterMillis] is the parsed `Retry-After` value, or
     * null when the response carried none.
     *
     * The longest back-off wins rather than the most recent one: responses that were already in
     * flight when the limit was hit arrive afterwards, and letting them shorten the window would
     * release the sweep straight back into the limit.
     */
    fun onRateLimited(retryAfterMillis: Long?) {
        val backoff = (retryAfterMillis ?: DEFAULT_BACKOFF_MS)
            .coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
        val until = System.currentTimeMillis() + backoff

        backoffUntil.updateAndGet { current -> max(current, until) }
    }

    companion object {
        /**
         * Spacing between paced requests. Reddit does not publish a figure for unauthenticated
         * clients, so this is deliberately conservative: the sweep it throttles is a background
         * refresh backed by a cache, and being slow costs nothing the user sees.
         */
        internal const val MIN_INTERVAL_MS = 500L

        /** Used when a 429 arrives without a `Retry-After` header. */
        internal const val DEFAULT_BACKOFF_MS = 10_000L

        internal const val MIN_BACKOFF_MS = 1_000L

        /**
         * A `Retry-After` far in the future would strand the app; past this point it is better to
         * try again and take another 429 than to stop fetching for minutes on end.
         */
        internal const val MAX_BACKOFF_MS = 60_000L
    }
}
