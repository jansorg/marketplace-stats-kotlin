/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import dev.ja.marketplace.client.HttpClientBuilder
import dev.ja.marketplace.client.mockHttpClientBuilder
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Made-up exchange rates in the format of the archive of the ECB: a ZIP file with a CSV file, the latest date first,
 * a comma at the end of each row and N/A for missing rates. The rates aren't real rates.
 */
object EcbTestData {
    val URL: URI = URI("https://ecb.example.com/eurofxref-hist.zip")

    val CSV: String = """
        Date,USD,JPY,BGN,CYP,GBP,
        2024-04-12,1.1000,160.00,1.9558,N/A,0.8500,
        2024-04-11,1.0900,159.00,1.9558,N/A,0.8400,
        2020-04-14,1.2000,120.00,1.9558,N/A,0.9000,
        2020-04-09,1.1500,118.00,1.9558,N/A,0.8800,
    """.trimIndent() + "\n"

    fun archive(csv: String = CSV): ByteArray {
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("eurofxref-hist.csv"))
                zip.write(csv.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
    }

    /** A mock server of [URL], which answers with the [archive] of [csv]. */
    fun httpClientBuilder(csv: String = CSV, status: HttpStatusCode = HttpStatusCode.OK): HttpClientBuilder {
        val archive = archive(csv)
        return mockHttpClientBuilder(MockEngine { request ->
            when (request.url.toString()) {
                URL.toString() -> respond(archive, status, headersOf(HttpHeaders.ContentType, "application/zip"))
                else -> respondError(HttpStatusCode.NotFound)
            }
        })
    }

    fun rateData(): EcbExchangeRateData = EcbExchangeRateData(dataUrl = URL, httpClientBuilder = httpClientBuilder())
}
