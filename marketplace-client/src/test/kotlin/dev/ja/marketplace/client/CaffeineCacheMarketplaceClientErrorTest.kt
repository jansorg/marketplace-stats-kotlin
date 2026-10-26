/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

class CaffeineCacheMarketplaceClientErrorTest {
    private val requests = AtomicInteger()

    private fun createClient(status: HttpStatusCode): CaffeineCacheMarketplaceClient {
        val engine = MockEngine {
            requests.incrementAndGet()
            respondError(status)
        }
        val httpClient = HttpClient(engine) {
            install(DefaultRequest) {
                url("https://${Marketplace.HOSTNAME}")
            }
            expectSuccess = true
        }
        return CaffeineCacheMarketplaceClient(httpClient = httpClient)
    }

    @Test
    suspend fun missingDataIsCached() {
        val client = createClient(HttpStatusCode.NotFound)
        repeat(2) { assertThrows<ClientRequestException> { client.pluginInfo(1) } }
        assertEquals(1, requests.get())
    }

    @Test
    suspend fun otherErrorsAreNotCached() {
        // e.g. too many requests, the next request tries again
        for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.Unauthorized)) {
            requests.set(0)
            val client = createClient(status)
            repeat(2) { assertThrows<ClientRequestException> { client.pluginInfo(1) } }
            assertEquals(2, requests.get(), "$status")
        }
    }
}
