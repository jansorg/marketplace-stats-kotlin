/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import dev.ja.marketplace.client.HttpClientFactory
import dev.ja.marketplace.client.mockHttpClientBuilder
import dev.ja.marketplace.client.persistentCache.PersistentCacheStore
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The download of the rates with the HTTP client of the application.
 */
class EcbExchangeRateDownloadTest {
    private val requests = CopyOnWriteArrayList<String>()

    private val archive = EcbTestData.archive("Date,USD,JPY,\n2026-10-27,1.1600,175.20,\n")

    private fun data(status: HttpStatusCode = HttpStatusCode.OK): EcbExchangeRateData {
        val engine = MockEngine { request ->
            requests += "${request.url} ${request.headers[HttpHeaders.UserAgent]}"
            respond(archive, status, headersOf(HttpHeaders.ContentType, "application/zip"))
        }
        val builder = mockHttpClientBuilder(engine).withUserAgent("marketplace-stats/1.2.3")
        return EcbExchangeRateData(dataUrl = URI("https://ecb.example.com/rates.zip"), httpClientBuilder = builder)
    }

    @Test
    fun ratesAreDownloaded() {
        val table = data().get()
        assertEquals(BigDecimal("1.16"), table.euroRate("USD", LocalDate.of(2026, 10, 27))?.stripTrailingZeros())
        assertEquals(listOf("https://ecb.example.com/rates.zip marketplace-stats/1.2.3"), requests)
    }

    @Test
    fun closeClosesTheHttpClient() {
        val data = data()
        // the client is created by the first download
        assertFalse(data.isHttpClientOpen)
        data.get()
        assertTrue(data.isHttpClientOpen)
        data.close()
        assertFalse(data.isHttpClientOpen)
    }

    @Test
    fun failedDownload() {
        assertThrows<ServerResponseException> { data(HttpStatusCode.InternalServerError).get() }
    }

    @Test
    fun downloadsAreNotRetried() {
        // get() blocks its callers while it downloads, a failure isn't retried with the retries of the builder
        val engine = MockEngine {
            requests += it.url.toString()
            respondError(HttpStatusCode.ServiceUnavailable)
        }
        val data = EcbExchangeRateData(dataUrl = URI("https://ecb.example.com/rates.zip"), httpClientBuilder = HttpClientFactory.builder().withEngine(engine))
        assertThrows<ServerResponseException> { data.get() }
        assertEquals(1, requests.size)
    }

    // like the ECB: Last-Modified, and 304 without the archive for the same If-Modified-Since
    private val lastModified = "Mon, 27 Oct 2026 15:00:00 GMT"
    private val ifModifiedSince = CopyOnWriteArrayList<String?>()
    private val conditionalEngine = MockEngine { request ->
        ifModifiedSince += request.headers[HttpHeaders.IfModifiedSince]
        when (request.headers[HttpHeaders.IfModifiedSince]) {
            lastModified -> respond(ByteArray(0), HttpStatusCode.NotModified, headersOf(HttpHeaders.LastModified, lastModified))
            else -> respond(archive, HttpStatusCode.OK, headersOf(HttpHeaders.LastModified, lastModified))
        }
    }

    @Test
    fun unchangedRatesAreValidated() {
        // the rates are outdated at once, the second get() updates them in the background
        val data = EcbExchangeRateData(dataUrl = URI("https://ecb.example.com/rates.zip"), maxAge = Duration.ZERO, httpClientBuilder = mockHttpClientBuilder(conditionalEngine))
        val table = data.get()
        data.get()
        data.awaitBackgroundRefresh()

        assertEquals(listOf(null, lastModified), ifModifiedSince)
        // unchanged rates are the same object, the values calculated with them stay valid
        assertSame(table, data.get())
    }

    @Test
    fun lastModifiedIsStored(@TempDir cacheDir: Path) {
        val store = PersistentCacheStore(cacheDir)
        EcbExchangeRateData(dataUrl = URI("https://ecb.example.com/rates.zip"), httpClientBuilder = mockHttpClientBuilder(conditionalEngine))
            .apply { enablePersistentCache(store) }
            .use { it.get() }

        // after a restart, the outdated stored rates are validated with the stored Last-Modified
        val restarted = EcbExchangeRateData(dataUrl = URI("https://ecb.example.com/rates.zip"), maxAge = Duration.ZERO, httpClientBuilder = mockHttpClientBuilder(conditionalEngine))
        restarted.enablePersistentCache(store)
        val table = restarted.get()
        restarted.awaitBackgroundRefresh()
        assertEquals(listOf(null, lastModified), ifModifiedSince)
        assertSame(table, restarted.get())
    }
}
