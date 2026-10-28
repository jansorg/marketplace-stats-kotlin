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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import dev.ja.marketplace.client.persistentCache.PersistentCacheStore
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class CaffeineCacheMarketplaceClientErrorTest {
    private val requests = AtomicInteger()

    private fun createClient(status: HttpStatusCode, store: PersistentCacheStore? = null): CaffeineCacheMarketplaceClient {
        val engine = MockEngine {
            requests.incrementAndGet()
            respondError(status)
        }
        return CaffeineCacheMarketplaceClient(httpClientBuilder = mockHttpClientBuilder(engine), persistentCache = store)
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

    @Test
    suspend fun serverErrorsAreCachedBriefly() {
        // after the retries of the HTTP client, the next requests, e.g. of the other tables of a page, don't try again
        val client = createClient(HttpStatusCode.ServiceUnavailable)
        repeat(2) { assertThrows<ServerResponseException> { client.pluginInfo(1) } }
        assertEquals(1, requests.get())
    }

    @Test
    suspend fun failedConnectionsAreCachedBriefly() {
        val engine = MockEngine {
            requests.incrementAndGet()
            throw IOException("connection refused")
        }
        val client = CaffeineCacheMarketplaceClient(httpClientBuilder = mockHttpClientBuilder(engine))
        repeat(2) { assertThrows<IOException> { client.pluginInfo(1) } }
        assertEquals(1, requests.get())
    }

    @Test
    suspend fun countryWithoutPricesIsCached() {
        // the Marketplace has no prices of the plugin in the country, it's cached like prices
        val client = createClient(HttpStatusCode.NotFound)
        repeat(2) { assertNull(client.priceInfo(1, "AT")) }
        assertEquals(1, requests.get())
    }

    @Test
    suspend fun unknownHostIsCachedBriefly() {
        // the Java engine fails with UnresolvedAddressException, which isn't an IOException
        val engine = MockEngine {
            requests.incrementAndGet()
            throw UnresolvedAddressException()
        }
        val client = CaffeineCacheMarketplaceClient(httpClientBuilder = mockHttpClientBuilder(engine))
        repeat(2) { assertThrows<UnresolvedAddressException> { client.pluginInfo(1) } }
        assertEquals(1, requests.get())
    }

    @Test
    suspend fun closeWritesThePendingPrices(@TempDir dir: Path) {
        val store = PersistentCacheStore(dir).accountStore(Marketplace.HOSTNAME, "key")
        createClient(HttpStatusCode.NotFound, store).use { client -> assertNull(client.priceInfo(1, "AT")) }

        // the prices are written with a delay, close writes them before it cancels the writes
        createClient(HttpStatusCode.NotFound, store).use { restarted -> assertNull(restarted.priceInfo(1, "AT")) }
        assertEquals(1, requests.get())
    }
}
