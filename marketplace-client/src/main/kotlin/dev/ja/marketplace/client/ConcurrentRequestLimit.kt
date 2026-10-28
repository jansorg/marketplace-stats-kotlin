/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.plugins.api.*
import io.ktor.client.request.*
import io.ktor.util.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val ChunkRequestKey = AttributeKey<Unit>("ChunkRequest")

/**
 * Marks a request of data, which is loaded in many small requests, e.g. the sales of each month or the prices of
 * each country. The number of concurrent chunk requests of a client is limited, see
 * [HttpClientBuilder.withMaxConcurrentChunkRequests].
 */
fun HttpRequestBuilder.chunkRequest() {
    attributes.put(ChunkRequestKey, Unit)
}

internal class ConcurrentRequestLimitConfig {
    var maxConcurrentRequests: Int = HttpClientBuilder.DEFAULT_MAX_CONCURRENT_REQUESTS
    var maxConcurrentChunkRequests: Int = HttpClientBuilder.DEFAULT_MAX_CONCURRENT_CHUNK_REQUESTS
}

/**
 * Limits the concurrent requests of a client, to avoid a high load on the JetBrains API, and with it the number of
 * its connections. Requests marked with [chunkRequest] have a lower limit of their own.
 *
 * Each attempt of a request holds a permit while it's sent and its response is received, a request doesn't hold it
 * while it waits for a retry. It must be installed after [io.ktor.client.plugins.HttpRequestRetry].
 * Streaming requests, e.g. `prepareGet(...).execute { }`, only hold the permit until the headers were received.
 */
internal val ConcurrentRequestLimit = createClientPlugin("ConcurrentRequestLimit", ::ConcurrentRequestLimitConfig) {
    val maxRequests = pluginConfig.maxConcurrentRequests
    val maxChunkRequests = pluginConfig.maxConcurrentChunkRequests
    require(maxRequests > 0) { "The maximum number of concurrent requests must be positive: $maxRequests" }
    require(maxChunkRequests > 0) { "The maximum number of concurrent chunk requests must be positive: $maxChunkRequests" }

    val permits = Semaphore(maxRequests)
    val chunkPermits = Semaphore(maxChunkRequests)
    on(Send) { request ->
        // always the permit of a chunk request first, so that waiting chunk requests don't hold permits of all requests
        when {
            request.attributes.contains(ChunkRequestKey) -> chunkPermits.withPermit { permits.withPermit { proceed(request) } }
            else -> permits.withPermit { proceed(request) }
        }
    }
}
