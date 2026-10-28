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
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The clients close the HTTP clients, which they created.
 */
class ClientCloseTest {
    @TempDir
    lateinit var tempDir: Path

    private val builder = mockHttpClientBuilder(MockEngine {
        respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    /**
     * Waits until the job of the HTTP client completed: a closed client completes its job after its coroutines are
     * done, the job of an open client never completes. `isActive` is still true while the coroutines finish.
     */
    private suspend fun HttpClient.awaitClosed() {
        val job = coroutineContext.job
        withTimeout(5.seconds) { job.join() }
        assertTrue(job.isCompleted)
    }

    @Test
    suspend fun marketplaceClient() {
        val client = KtorMarketplaceClient(httpClientBuilder = builder)
        // also after a request, whose coroutines may still finish
        client.vendors()
        assertFalse(client.httpClient.coroutineContext.job.isCompleted)
        client.close()
        client.httpClient.awaitClosed()
    }

    @Test
    suspend fun cachingMarketplaceClient() {
        val store = PersistentCacheStore(tempDir).accountStore(Marketplace.HOSTNAME, "key")
        val client = CaffeineCacheMarketplaceClient(httpClientBuilder = builder, persistentCache = store)
        client.vendors()
        client.close()
        client.httpClient.awaitClosed()
    }

    @Test
    suspend fun jetBrainsServiceClient() {
        val client = KtorJetBrainsServiceClient(builder)
        client.close()
        client.httpClient.awaitClosed()
    }

    @Test
    suspend fun openClientIsNotCompleted() {
        val client = KtorJetBrainsServiceClient(builder)
        assertNull(withTimeoutOrNull(100.milliseconds) { client.httpClient.coroutineContext.job.join() })
        client.close()
    }
}
