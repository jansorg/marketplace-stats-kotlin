/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import dev.ja.marketplace.client.HttpClientFactory
import dev.ja.marketplace.client.mockHttpClientBuilder
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun unchangedRatesAreValidated(@TempDir cacheDir: Path) {
        // like the ECB: Last-Modified, and 304 for the same If-Modified-Since
        val lastModified = "Mon, 27 Oct 2026 15:00:00 GMT"
        val ifModifiedSince = CopyOnWriteArrayList<String?>()
        val engine = MockEngine { request ->
            ifModifiedSince += request.headers[HttpHeaders.IfModifiedSince]
            when (request.headers[HttpHeaders.IfModifiedSince]) {
                lastModified -> respond(ByteArray(0), HttpStatusCode.NotModified, headersOf(HttpHeaders.LastModified, lastModified))
                else -> respond(archive, HttpStatusCode.OK, headersOf(HttpHeaders.LastModified to listOf(lastModified), HttpHeaders.CacheControl to listOf("max-age=0")))
            }
        }
        val builder = mockHttpClientBuilder(engine).withHttpCacheDirectory(cacheDir)
        // the rates are outdated at once, the second get() updates them in the background
        val data = EcbExchangeRateData(dataUrl = URI("https://ecb.example.com/rates.zip"), maxAge = Duration.ZERO, httpClientBuilder = builder)
        val table = data.get()
        data.get()
        data.awaitBackgroundRefresh()

        assertEquals(listOf(null, lastModified), ifModifiedSince)
        assertEquals(table.euroRate("USD", LocalDate.of(2026, 10, 27)), data.get().euroRate("USD", LocalDate.of(2026, 10, 27)))
    }
}
