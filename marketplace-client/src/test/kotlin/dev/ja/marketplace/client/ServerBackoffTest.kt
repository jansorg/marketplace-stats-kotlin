/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.date.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

class ServerBackoffTest {
    @Test
    fun pauseOfRetryAfter() {
        val now = 1_700_000_000_000L
        assertEquals(30.seconds, ServerBackoff.pauseOf("30", now))
        assertEquals(ServerBackoff.DEFAULT_PAUSE, ServerBackoff.pauseOf(null, now))
        assertEquals(ServerBackoff.DEFAULT_PAUSE, ServerBackoff.pauseOf("soon", now))
        // a long pause is shortened, a date in the past is no pause
        assertEquals(ServerBackoff.MAX_PAUSE, ServerBackoff.pauseOf("86400", now))
        assertEquals(Duration.ZERO, ServerBackoff.pauseOf("-5", now))

        // an HTTP date in English
        val date = GMTDate(now + 20_000).toHttpDate()
        assertEquals(20.seconds, ServerBackoff.pauseOf(date, now))
        assertEquals(Duration.ZERO, ServerBackoff.pauseOf(GMTDate(now - 20_000).toHttpDate(), now))
    }

    @Test
    fun pauseEndsAfterItsTime() {
        val time = TestTimeSource()
        val backoff = ServerBackoff(time)
        assertEquals(Duration.ZERO, backoff.remainingPause())

        backoff.pause(10.seconds)
        time += 4.seconds
        assertEquals(6.seconds, backoff.remainingPause())

        // a shorter pause doesn't shorten the current pause
        backoff.pause(1.seconds)
        assertEquals(6.seconds, backoff.remainingPause())
        backoff.pause(1.minutes)
        assertEquals(1.minutes, backoff.remainingPause())

        time += 2.minutes
        assertEquals(Duration.ZERO, backoff.remainingPause())
    }

    @Test
    suspend fun tooManyRequestsPausesAllRequests() = coroutineScope {
        val start = TimeSource.Monotonic.markNow()
        val times = CopyOnWriteArrayList<Duration>()
        val engine = MockEngine { request ->
            times += start.elapsedNow()
            when (request.url.encodedPath) {
                "/limited" -> respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1"))
                else -> respond("ok", HttpStatusCode.OK)
            }
        }

        mockHttpClientBuilder(engine).build().use { client ->
            assertThrows<ClientRequestException> { client.get("/limited") }
            val other = async { client.get("/other").bodyAsText() }
            delay(10)
            assertEquals("ok", other.await())
        }

        // the second request waited for the pause of a second
        assertEquals(2, times.size)
        assertTrue(times[1] - times[0] >= 950.milliseconds, "request times: $times")
    }
}
