/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class HttpClientTimeoutTest {
    private val requests = AtomicInteger()

    /** The first [slowRequests] requests take [slowTime], the other requests respond immediately. */
    private fun engine(slowTime: Duration, slowRequests: Int = Int.MAX_VALUE) = MockEngine {
        if (requests.incrementAndGet() <= slowRequests) {
            delay(slowTime)
        }
        respond("ok", HttpStatusCode.OK)
    }

    @Test
    suspend fun requestTimeout() {
        mockHttpClientBuilder(engine(1_000.milliseconds)).withRequestTimeout(100.milliseconds).build().use { client ->
            assertThrows<HttpRequestTimeoutException> { client.get("/slow").bodyAsText() }
        }
    }

    @Test
    suspend fun requestOverridesTheTimeout() {
        mockHttpClientBuilder(engine(300.milliseconds)).withRequestTimeout(100.milliseconds).build().use { client ->
            val response = client.get("/download") {
                timeout { requestTimeoutMillis = 2_000 }
            }
            assertEquals("ok", response.bodyAsText())
        }
    }

    @Test
    suspend fun timedOutAttemptIsRetried() {
        val builder = mockHttpClientBuilder(engine(1_000.milliseconds, slowRequests = 1))
            .withRequestRetry(true)
            .withRequestTimeout(100.milliseconds)
        builder.build().use { client ->
            assertEquals("ok", client.get("/slow").bodyAsText())
        }
        assertEquals(2, requests.get())
    }

    @Test
    suspend fun timeoutIsRetriedOnce() {
        val builder = mockHttpClientBuilder(engine(1_000.milliseconds))
            .withRequestRetry(true)
            .withRequestTimeout(100.milliseconds)
        builder.build().use { client ->
            assertThrows<HttpRequestTimeoutException> { client.get("/slow").bodyAsText() }
        }
        assertEquals(2, requests.get())
    }

    @Test
    suspend fun waitingForChunkPermitDoesNotCount() = coroutineScope {
        // the second request waits 150 ms for the permit, the timeout applies to its own 150 ms
        val builder = mockHttpClientBuilder(engine(150.milliseconds))
            .withMaxConcurrentChunkRequests(1)
            .withRequestTimeout(250.milliseconds)
        builder.build().use { client ->
            val bodies = (1..2).map { async { client.get("/chunk") { chunkRequest() }.bodyAsText() } }.awaitAll()
            assertEquals(listOf("ok", "ok"), bodies)
        }
    }

    @Test
    suspend fun releaseDownloadHasNoRequestTimeout(@TempDir dir: Path) {
        val builder = mockHttpClientBuilder(engine(300.milliseconds)).withRequestTimeout(100.milliseconds)
        val target = dir.resolve("release.zip")
        KtorMarketplaceClient(httpClientBuilder = builder).use { client ->
            client.downloadRelease(target, 1)
        }
        assertEquals("ok", target.readText())
    }

    @Test
    fun timeoutsMustBePositive() {
        assertThrows<IllegalArgumentException> { HttpClientFactory.builder().withRequestTimeout(Duration.ZERO) }
        assertThrows<IllegalArgumentException> { HttpClientFactory.builder().withConnectTimeout((-1).milliseconds) }
        assertThrows<IllegalArgumentException> { HttpClientFactory.builder().withSocketTimeout(Duration.ZERO) }
    }
}
