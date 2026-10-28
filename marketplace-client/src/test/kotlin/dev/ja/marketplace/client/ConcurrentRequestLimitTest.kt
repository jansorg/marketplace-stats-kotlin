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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class ConcurrentRequestLimitTest {
    private val active = AtomicInteger()
    private val maxActive = AtomicInteger()
    private val requests = AtomicInteger()

    // each request takes a while, so that concurrent requests overlap
    private val engine = MockEngine { request ->
        requests.incrementAndGet()
        maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
        try {
            delay(50)
        } finally {
            active.decrementAndGet()
        }
        val body = if (request.url.encodedPath.endsWith("-info")) "[]" else "{}"
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @Test
    suspend fun chunkRequestsAreLimited() = coroutineScope {
        mockHttpClientBuilder(engine).withMaxConcurrentChunkRequests(2).build().use { client ->
            (1..8).map { async { client.get("/chunk") { chunkRequest() }.bodyAsText() } }.awaitAll()
        }
        assertEquals(8, requests.get())
        assertEquals(2, maxActive.get())
    }

    @Test
    suspend fun otherRequestsAreNotLimited() = coroutineScope {
        mockHttpClientBuilder(engine).withMaxConcurrentChunkRequests(2).build().use { client ->
            (1..8).map { async { client.get("/other").bodyAsText() } }.awaitAll()
        }
        // more than the limit, not all requests necessarily overlap on a busy machine
        assertTrue(maxActive.get() > 2, "max. concurrent requests: ${maxActive.get()}")
    }

    @Test
    suspend fun clientsHaveTheirOwnLimit() = coroutineScope {
        val builder = mockHttpClientBuilder(engine).withMaxConcurrentChunkRequests(1)
        builder.build().use { first ->
            builder.build().use { second ->
                (1..4).map { index ->
                    val client = if (index % 2 == 0) first else second
                    async { client.get("/chunk") { chunkRequest() }.bodyAsText() }
                }.awaitAll()
            }
        }
        assertEquals(2, maxActive.get())
    }

    @Test
    suspend fun cancelledRequestReleasesItsPermit() = coroutineScope {
        mockHttpClientBuilder(engine).withMaxConcurrentChunkRequests(1).build().use { client ->
            val running = async { client.get("/chunk") { chunkRequest() }.bodyAsText() }
            val waiting = async { client.get("/chunk") { chunkRequest() }.bodyAsText() }
            delay(10)
            running.cancel()
            waiting.cancel()
            // a request after the cancelled ones gets a permit
            assertEquals("{}", withTimeout(2_000) { client.get("/chunk") { chunkRequest() }.bodyAsText() })
        }
    }

    @Test
    fun limitMustBePositive() {
        assertThrows<IllegalArgumentException> { HttpClientFactory.builder().withMaxConcurrentChunkRequests(0) }
        assertThrows<IllegalArgumentException> { HttpClientFactory.builder().withMaxConcurrentRequests(0) }
    }

    @Test
    suspend fun allRequestsAreLimited() = coroutineScope {
        mockHttpClientBuilder(engine).withMaxConcurrentRequests(3).build().use { client ->
            (1..9).map { async { client.get("/other").bodyAsText() } }.awaitAll()
        }
        assertEquals(9, requests.get())
        assertTrue(maxActive.get() in 2..3, "max. concurrent requests: ${maxActive.get()}")
    }

    @Test
    suspend fun waitingChunkRequestsDontBlockOtherRequests() = coroutineScope {
        val start = TimeSource.Monotonic.markNow()
        mockHttpClientBuilder(engine).withMaxConcurrentRequests(2).withMaxConcurrentChunkRequests(1).build().use { client ->
            // the chunk requests take 5 * 50 ms one after the other, the other request uses the second permit
            val chunks = (1..5).map { async { client.get("/chunk") { chunkRequest() }.bodyAsText() } }
            delay(10)
            client.get("/other").bodyAsText()
            val otherDone = start.elapsedNow()
            chunks.awaitAll()
            assertTrue(otherDone < 200.milliseconds, "other request done after $otherDone")
        }
    }

    @Test
    suspend fun salesOfAllMonthsAreLimited() {
        KtorMarketplaceClient(httpClientBuilder = mockHttpClientBuilder(engine).withMaxConcurrentChunkRequests(3)).use { client ->
            client.salesInfo(1, YearMonthDayRange(YearMonthDay(2024, 1, 1), YearMonthDay(2024, 12, 31)))
        }
        // a request of each month
        assertEquals(12, requests.get())
        assertEquals(3, maxActive.get())
    }
}
