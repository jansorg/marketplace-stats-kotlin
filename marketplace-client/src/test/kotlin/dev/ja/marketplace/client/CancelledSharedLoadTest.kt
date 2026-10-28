/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrent requests share the load of a value, which runs in the coroutine of the first request. A cancelled request,
 * e.g. of a chart, which the browser aborted, must not fail the other requests.
 */
class CancelledSharedLoadTest {
    private val requests = AtomicInteger()

    private fun createClient(): CaffeineCacheMarketplaceClient {
        val engine = MockEngine {
            requests.incrementAndGet()
            delay(300)
            respond("""["", "eap"]""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return CaffeineCacheMarketplaceClient(httpClientBuilder = mockHttpClientBuilder(engine))
    }

    @Test
    suspend fun theOtherRequestsLoadAgainAfterACancellation() = coroutineScope {
        createClient().use { client ->
            val first = async { client.channels(1) }
            delay(100)
            val second = async { client.channels(1) }
            delay(100)
            // e.g. the browser aborted the first request
            first.cancel()

            assertEquals(listOf("", "eap"), second.await())
            assertTrue(runCatching { first.await() }.exceptionOrNull() is CancellationException)
            // the second request loaded the value again
            assertEquals(2, requests.get())
        }
    }

    @Test
    suspend fun theLoadIsntCancelledByACancelledWaiter() = coroutineScope {
        createClient().use { client ->
            val first = async { client.channels(1) }
            delay(100)
            val second = async { client.channels(1) }
            delay(100)
            // e.g. the browser aborted the second request, which waits for the load of the first one
            second.cancel()

            assertEquals(listOf("", "eap"), first.await())
            assertTrue(runCatching { second.await() }.exceptionOrNull() is CancellationException)
            // the load of the first request wasn't restarted
            assertEquals(1, requests.get())
        }
    }
}
