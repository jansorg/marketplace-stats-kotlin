/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.plugins.api.*
import io.ktor.client.request.*
import io.ktor.util.*
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val ChunkRequestKey = AttributeKey<Unit>("ChunkRequest")
private val BackgroundRequestKey = AttributeKey<Unit>("BackgroundRequest")

/**
 * Marks a request of data, which is loaded in many small requests, e.g. the sales of each month or the prices of
 * each country. The number of concurrent chunk requests of a client is limited, see
 * [HttpClientBuilder.withMaxConcurrentChunkRequests].
 *
 * @param background If the request is made in the background, e.g. of an update of the stored history, see
 *  [BackgroundRequests]. Background requests have a lower limit, so that they don't delay the requests of pages.
 */
fun HttpRequestBuilder.chunkRequest(background: Boolean = false) {
    attributes.put(ChunkRequestKey, Unit)
    if (background) {
        attributes.put(BackgroundRequestKey, Unit)
    }
}

/**
 * Marks the coroutines of work in the background, e.g. updates of the stored history, the automatic refresh or the
 * summary of all plugins at the start. Their chunk requests are background requests, see [chunkRequest].
 */
data object BackgroundRequests : CoroutineContext.Element, CoroutineContext.Key<BackgroundRequests> {
    override val key: CoroutineContext.Key<*>
        get() = this

    /** @return If the current coroutine works in the background. */
    suspend fun isActive(): Boolean = currentCoroutineContext()[BackgroundRequests] != null
}

internal class ConcurrentRequestLimitConfig {
    var maxConcurrentRequests: Int = HttpClientBuilder.DEFAULT_MAX_CONCURRENT_REQUESTS
    var maxConcurrentChunkRequests: Int = HttpClientBuilder.DEFAULT_MAX_CONCURRENT_CHUNK_REQUESTS
    var maxConcurrentBackgroundRequests: Int = HttpClientBuilder.DEFAULT_MAX_CONCURRENT_BACKGROUND_REQUESTS
}

/**
 * Limits the concurrent requests of a client, to avoid a high load on the JetBrains API, and with it the number of
 * its connections. Requests marked with [chunkRequest] have a lower limit of their own, and background chunk requests
 * an even lower limit, so that a page waits at most for a few background requests.
 *
 * Each attempt of a request holds a permit while it's sent and its response is received, a request doesn't hold it
 * while it waits for a retry. It must be installed after [io.ktor.client.plugins.HttpRequestRetry].
 * Streaming requests, e.g. `prepareGet(...).execute { }`, only hold the permit until the headers were received.
 */
internal val ConcurrentRequestLimit = createClientPlugin("ConcurrentRequestLimit", ::ConcurrentRequestLimitConfig) {
    val maxRequests = pluginConfig.maxConcurrentRequests
    val maxChunkRequests = pluginConfig.maxConcurrentChunkRequests
    val maxBackgroundRequests = pluginConfig.maxConcurrentBackgroundRequests
    require(maxRequests > 0) { "The maximum number of concurrent requests must be positive: $maxRequests" }
    require(maxChunkRequests > 0) { "The maximum number of concurrent chunk requests must be positive: $maxChunkRequests" }
    require(maxBackgroundRequests > 0) { "The maximum number of concurrent background requests must be positive: $maxBackgroundRequests" }

    val permits = Semaphore(maxRequests)
    val chunkPermits = Semaphore(maxChunkRequests)
    val backgroundPermits = Semaphore(maxBackgroundRequests)
    on(Send) { request ->
        // The permits are taken in the same order, from the lowest limit to the highest limit, so that waiting
        // requests don't hold the permits of the higher limits, e.g. background requests the permits of chunk requests.
        val isChunk = request.attributes.contains(ChunkRequestKey)
        val isBackground = request.attributes.contains(BackgroundRequestKey)
        when {
            isChunk && isBackground -> backgroundPermits.withPermit { chunkPermits.withPermit { permits.withPermit { proceed(request) } } }
            isChunk -> chunkPermits.withPermit { permits.withPermit { proceed(request) } }
            else -> permits.withPermit { proceed(request) }
        }
    }
}
