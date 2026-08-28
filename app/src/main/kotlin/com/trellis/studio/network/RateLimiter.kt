package com.trellis.studio.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the app under a requests-per-minute ceiling.
 *
 * NVIDIA rate-limits per account, and the app fires several requests at once by
 * design — the 3D queue races two lanes per attempt, and a batch export can
 * stack calls behind each other. Without a shared limiter those bursts collect
 * 429s, and a 429 in the middle of a queue looks to the user like the feature is
 * broken rather than like it went too fast.
 *
 * A sliding window rather than a token bucket: the ceiling NVIDIA publishes is
 * "N per minute", and a window matches that exactly instead of approximating it.
 */
class RateLimiter(private val perMinute: Int) {

    private val mutex = Mutex()
    private val recent = ArrayDeque<Long>()

    /** Suspends until another request may go out, then records it. */
    suspend fun acquire(now: () -> Long = System::currentTimeMillis) {
        while (true) {
            val wait = mutex.withLock {
                val cutoff = now() - WINDOW_MS
                while (recent.isNotEmpty() && recent.first() <= cutoff) recent.removeFirst()
                if (recent.size < perMinute) {
                    recent.addLast(now())
                    return
                }
                // The oldest call is what frees the next slot.
                (recent.first() + WINDOW_MS) - now()
            }
            if (wait > 0) delay(wait.coerceAtMost(WINDOW_MS))
        }
    }

    /** Requests made inside the current window, for progress text and tests. */
    suspend fun inFlight(now: () -> Long = System::currentTimeMillis): Int = mutex.withLock {
        val cutoff = now() - WINDOW_MS
        while (recent.isNotEmpty() && recent.first() <= cutoff) recent.removeFirst()
        recent.size
    }

    /**
     * Runs [block], retrying while it reports a rate limit.
     *
     * [retryAfterMillis] returns the server's own Retry-After when it sent one —
     * guessing a delay when the server has already said how long to wait is how
     * a client ends up hammering a limit it was told about.
     */
    suspend fun <T> withRetry(
        attempts: Int = MAX_ATTEMPTS,
        isRateLimited: (Throwable) -> Boolean = ::looksRateLimited,
        retryAfterMillis: (Throwable) -> Long? = { null },
        block: suspend () -> T,
    ): Result<T> {
        var last: Throwable? = null
        repeat(attempts) { attempt ->
            acquire()
            val result = runCatching { block() }
            result.onSuccess { return Result.success(it) }
            result.onFailure { error ->
                last = error
                if (!isRateLimited(error) || attempt == attempts - 1) return Result.failure(error)
                // Exponential, because a limit that is already saturated will not
                // clear on the same schedule for every waiting caller.
                val backoff = retryAfterMillis(error)
                    ?: (BASE_BACKOFF_MS shl attempt).coerceAtMost(MAX_BACKOFF_MS)
                delay(backoff)
            }
        }
        return Result.failure(last ?: Exception("Rate limited."))
    }

    companion object {
        const val WINDOW_MS = 60_000L
        private const val MAX_ATTEMPTS = 4
        private const val BASE_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L

        /** NVIDIA's published ceiling for this account tier. */
        const val NVIDIA_PER_MINUTE = 40

        /** Shared across every client, since the limit is per account, not per endpoint. */
        val nvidia by lazy { RateLimiter(NVIDIA_PER_MINUTE) }

        fun looksRateLimited(e: Throwable): Boolean {
            val m = e.message.orEmpty()
            return m.contains("429") || m.contains("rate limit", true) ||
                m.contains("too many requests", true)
        }
    }
}
