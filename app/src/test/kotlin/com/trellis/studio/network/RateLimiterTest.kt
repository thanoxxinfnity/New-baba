package com.trellis.studio.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The limiter exists so a burst of parallel calls does not collect 429s. These
 * drive it with a fake clock, because a test that really waited a minute would
 * be too slow to run and too flaky to trust.
 */
class RateLimiterTest {

    private class FakeClock(var millis: Long = 1_000_000L) {
        fun now() = millis
        fun advance(by: Long) { millis += by }
    }

    @Test
    fun `requests under the ceiling do not wait`() = runTest {
        val clock = FakeClock()
        val limiter = RateLimiter(perMinute = 5)
        repeat(5) { limiter.acquire(clock::now) }
        assertEquals(5, limiter.inFlight(clock::now))
    }

    @Test
    fun `the window slides so old calls stop counting`() = runTest {
        val clock = FakeClock()
        val limiter = RateLimiter(perMinute = 3)
        repeat(3) { limiter.acquire(clock::now) }
        assertEquals(3, limiter.inFlight(clock::now))

        // One second past the window, every earlier call has expired.
        clock.advance(RateLimiter.WINDOW_MS + 1_000)
        assertEquals(0, limiter.inFlight(clock::now))
        limiter.acquire(clock::now)
        assertEquals(1, limiter.inFlight(clock::now))
    }

    @Test
    fun `a burst is held to the ceiling`() = runTest {
        val limiter = RateLimiter(perMinute = 4)
        val started = AtomicInteger()
        // The limiter's clock is the test scheduler's, so a delay inside it moves
        // time forward exactly as it would in reality — with a frozen clock the
        // waiting callers would spin in virtual time and never settle.
        val clock = { testScheduler.currentTime }

        val jobs = List(10) {
            async {
                limiter.acquire(clock)
                started.incrementAndGet()
            }
        }

        // Nothing has been allowed to wait out a window yet.
        testScheduler.advanceTimeBy(1)
        assertEquals("only the ceiling should have been admitted", 4, started.get())

        jobs.awaitAll()
        assertEquals("the rest go through as windows clear", 10, started.get())
        assertTrue(
            "ten calls at four a minute must have taken more than a minute",
            testScheduler.currentTime >= RateLimiter.WINDOW_MS,
        )
    }

    @Test
    fun `a rate limited call is retried and can succeed`() = runTest {
        val limiter = RateLimiter(perMinute = 100)
        var calls = 0
        val result = limiter.withRetry(
            retryAfterMillis = { 0L },
        ) {
            calls++
            if (calls < 3) throw RateLimitedException() else "done"
        }
        assertEquals("done", result.getOrNull())
        assertEquals("should have taken three tries", 3, calls)
    }

    @Test
    fun `a non rate limit failure is not retried`() = runTest {
        val limiter = RateLimiter(perMinute = 100)
        var calls = 0
        val result = limiter.withRetry { calls++; throw Exception("model not found") }
        assertTrue(result.isFailure)
        assertEquals("a real error must surface immediately", 1, calls)
    }

    @Test
    fun `retries give up rather than looping forever`() = runTest {
        val limiter = RateLimiter(perMinute = 100)
        var calls = 0
        val result = limiter.withRetry(attempts = 3, retryAfterMillis = { 0L }) {
            calls++; throw RateLimitedException()
        }
        assertTrue(result.isFailure)
        assertEquals(3, calls)
        assertTrue(
            "the message should say what happened",
            RateLimiter.looksRateLimited(result.exceptionOrNull()!!),
        )
    }

    @Test
    fun `the server's own retry-after is honoured`() = runTest {
        val limiter = RateLimiter(perMinute = 100)
        var calls = 0
        var asked: Long? = null
        limiter.withRetry(
            retryAfterMillis = { e -> (e as RateLimitedException).retryAfterMs.also { asked = it } },
        ) {
            calls++
            if (calls < 2) throw RateLimitedException(retryAfterMs = 5_000) else "ok"
        }
        assertEquals("the server's delay should be used, not a guess", 5_000L, asked)
    }

    @Test
    fun `429 text is recognised however it is phrased`() {
        listOf(
            "API error (429): slow down",
            "Rate limit reached",
            "Too Many Requests",
        ).forEach {
            assertTrue("should be treated as a rate limit: $it", RateLimiter.looksRateLimited(Exception(it)))
        }
        assertTrue("a 404 must not be", !RateLimiter.looksRateLimited(Exception("404 not found")))
    }
}
