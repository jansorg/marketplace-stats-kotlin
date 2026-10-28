/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.persistentCache.PersistentCacheStore
import dev.ja.marketplace.services.KtorJetBrainsServiceClient
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The clients close the HTTP clients, which they created.
 */
class ClientCloseTest {
    @TempDir
    lateinit var tempDir: Path

    private val builder = mockHttpClientBuilder(MockEngine { respond("", HttpStatusCode.NotFound) })

    private val HttpClient.isActive: Boolean
        get() = coroutineContext[Job]!!.isActive

    @Test
    fun marketplaceClient() {
        val client = KtorMarketplaceClient(httpClientBuilder = builder)
        assertTrue(client.httpClient.isActive)
        client.close()
        assertFalse(client.httpClient.isActive)
    }

    @Test
    fun cachingMarketplaceClient() {
        val store = PersistentCacheStore(tempDir).accountStore(Marketplace.HOSTNAME, "key")
        val client = CaffeineCacheMarketplaceClient(httpClientBuilder = builder, persistentCache = store)
        client.close()
        assertFalse(client.httpClient.isActive)
    }

    @Test
    fun jetBrainsServiceClient() {
        val client = KtorJetBrainsServiceClient(builder)
        assertTrue(client.httpClient.isActive)
        client.close()
        assertFalse(client.httpClient.isActive)
    }
}
