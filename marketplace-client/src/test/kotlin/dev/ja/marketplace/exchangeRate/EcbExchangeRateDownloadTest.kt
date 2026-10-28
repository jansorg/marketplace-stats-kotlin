/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import dev.ja.marketplace.client.mockHttpClientBuilder
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The download of the rates with the HTTP client of the application.
 */
class EcbExchangeRateDownloadTest {
    private val requests = CopyOnWriteArrayList<String>()

    private val archive = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("eurofxref-hist.csv"))
            zip.write("Date,USD,JPY,\n2026-10-27,1.1600,175.20,\n".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

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
}
