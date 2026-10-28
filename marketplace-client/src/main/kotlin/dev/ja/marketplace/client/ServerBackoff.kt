/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.plugins.api.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.coroutines.delay
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal val SlowRequestKey = AttributeKey<Unit>("SlowRequest")

/**
 * Marks a request, which the server answers with 503 if it takes too long, e.g. a request of the downloads statistics
 * of a long time range. The 503 isn't a temporary problem of the server: it isn't retried, see [HttpClientBuilder], and
 * doesn't pause the other requests, see [ServerBackoff].
 */
fun HttpRequestBuilder.slowRequest() {
    attributes.put(SlowRequestKey, Unit)
}

/**
 * The pause of all requests of a client after the server answered with too many requests (429) or service unavailable
 * (503), until the time of its `Retry-After` header.
 */
internal class ServerBackoff(private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic) {
    // guarded by this
    private var pausedUntil: ComparableTimeMark? = null

    /** @return The time until requests may be sent again, [Duration.ZERO] if they aren't paused. */
    fun remainingPause(): Duration = synchronized(this) {
        val until = pausedUntil ?: return Duration.ZERO
        (-until.elapsedNow()).coerceAtLeast(Duration.ZERO)
    }

    /** Pauses the requests for [duration], a longer pause, which already started, is kept. */
    fun pause(duration: Duration) = synchronized(this) {
        val until = timeSource.markNow() + duration
        if (pausedUntil.let { it == null || it < until }) {
            pausedUntil = until
        }
    }

    companion object {
        /** The pause without a valid `Retry-After` header. */
        val DEFAULT_PAUSE: Duration = 1.seconds

        /** The longest pause, a longer `Retry-After` is shortened. */
        val MAX_PAUSE: Duration = 2.minutes

        /**
         * @param retryAfter The value of the `Retry-After` header: seconds or an HTTP date.
         * @return The pause requested by the server, at least [Duration.ZERO] and at most [MAX_PAUSE].
         */
        fun pauseOf(retryAfter: String?, nowMillis: Long = System.currentTimeMillis()): Duration {
            val value = retryAfter?.trim()?.takeIf(String::isNotEmpty) ?: return DEFAULT_PAUSE
            val pause = value.toLongOrNull()?.seconds
                ?: runCatching { (value.fromHttpToGmtDate().timestamp - nowMillis).milliseconds }.getOrNull()
                ?: DEFAULT_PAUSE
            return pause.coerceIn(Duration.ZERO, MAX_PAUSE)
        }
    }
}

/**
 * Pauses all requests of a client after a response with status 429 or 503, see [ServerBackoff]. The request itself is
 * retried by [io.ktor.client.plugins.HttpRequestRetry], the other requests don't add to the load meanwhile.
 * It must be installed after the retries and before [RequestRateLimit], so that the paused requests are sent at the
 * rate of the limit afterward.
 */
internal val ServerBackoffPlugin = createClientPlugin("ServerBackoff") {
    val backoff = ServerBackoff()
    on(Send) { request ->
        delay(backoff.remainingPause())
        val call = proceed(request)
        val status = call.response.status
        val isTooSlow = status == HttpStatusCode.ServiceUnavailable && request.attributes.contains(SlowRequestKey)
        if (!isTooSlow && (status == HttpStatusCode.TooManyRequests || status == HttpStatusCode.ServiceUnavailable)) {
            backoff.pause(ServerBackoff.pauseOf(call.response.headers[HttpHeaders.RetryAfter]))
        }
        call
    }
}
