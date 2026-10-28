/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

class RequestRateLimitTest {
    @Test
    fun requestsWithinTheLimitDontWait() {
        val limiter = RequestRateLimiter(3, 1.seconds, TestTimeSource())
        assertEquals(List(3) { Duration.ZERO }, List(3) { limiter.reserve() })
    }

    @Test
    fun requestsBeyondTheLimitWaitForTheWindow() {
        val time = TestTimeSource()
        val limiter = RequestRateLimiter(2, 1.seconds, time)
        limiter.reserve()
        time += 400.milliseconds
        limiter.reserve()

        // the 3rd request waits until the 1st request left the window, the 4th until the 2nd left it
        assertEquals(600.milliseconds, limiter.reserve())
        assertEquals(1_000.milliseconds, limiter.reserve())
        // the 5th request waits for the reserved time of the 3rd request
        assertEquals(1_600.milliseconds, limiter.reserve())
    }

    @Test
    fun noWaitAfterAQuietPeriod() {
        val time = TestTimeSource()
        val limiter = RequestRateLimiter(2, 1.seconds, time)
        repeat(2) { limiter.reserve() }
        time += 5.seconds
        assertEquals(Duration.ZERO, limiter.reserve())
        assertEquals(Duration.ZERO, limiter.reserve())
    }

    @Test
    suspend fun clientLimitsTheRequests() = coroutineScope {
        val start = TimeSource.Monotonic.markNow()
        val times = CopyOnWriteArrayList<Duration>()
        val engine = MockEngine {
            times += start.elapsedNow()
            respond("ok", HttpStatusCode.OK)
        }

        mockHttpClientBuilder(engine).withRateLimit(2, 300.milliseconds).build().use { client ->
            (1..5).map { async { client.get("/item").bodyAsText() } }.awaitAll()
        }

        val sorted = times.sorted()
        assertEquals(5, sorted.size)
        // any 300 ms contain at most 2 requests
        for (i in 2 until sorted.size) {
            assertTrue(sorted[i] - sorted[i - 2] >= 290.milliseconds, "request times: $sorted")
        }
    }

    @Test
    fun invalidLimits() {
        assertThrows<IllegalArgumentException> { HttpClientFactory.builder().withRateLimit(0) }
        assertThrows<IllegalArgumentException> { HttpClientFactory.builder().withRateLimit(1, Duration.ZERO) }
    }
}
