/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.plugins.api.*
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The times of the requests, which are allowed by a limit of [maxRequests] requests per [period]: a sliding window,
 * any [period] contains at most [maxRequests] requests.
 */
internal class RequestRateLimiter(
    private val maxRequests: Int,
    private val period: Duration,
    timeSource: TimeSource = TimeSource.Monotonic,
) {
    init {
        require(maxRequests > 0) { "The maximum number of requests must be positive: $maxRequests" }
        require(period.isPositive()) { "The period of the rate limit must be positive: $period" }
    }

    private val start = timeSource.markNow()

    // the times of the latest requests, relative to start, in ascending order
    private val times = ArrayDeque<Duration>(maxRequests)

    /**
     * Reserves the time of a request.
     * @return The time to wait until the request may be sent, [Duration.ZERO] if it may be sent now.
     */
    fun reserve(): Duration = synchronized(this) {
        val now = start.elapsedNow()
        val time = when {
            times.size < maxRequests -> now
            else -> maxOf(now, times.removeFirst() + period)
        }
        times.addLast(time)
        time - now
    }
}

internal class RequestRateLimitConfig {
    var maxRequests: Int = 1
    var period: Duration = Duration.INFINITE
}

/**
 * Limits the number of requests per period, see [RequestRateLimiter]. Each attempt of a request counts, a request
 * doesn't wait for the limit while it waits for a retry. It must be installed after
 * [io.ktor.client.plugins.HttpRequestRetry] and before [io.ktor.client.plugins.HttpTimeout], so that the time waiting
 * for the limit doesn't count for the timeout of the request.
 */
internal val RequestRateLimit = createClientPlugin("RequestRateLimit", ::RequestRateLimitConfig) {
    val limiter = RequestRateLimiter(pluginConfig.maxRequests, pluginConfig.period)
    on(Send) { request ->
        delay(limiter.reserve())
        proceed(request)
    }
}
