/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import dev.ja.marketplace.client.mockHttpClientBuilder
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The products of a mock server with made-up data in the format of `/products` of the data services of JetBrains,
 * see [JetBrainsServicesLiveTest] for the real products.
 */
class ProductReleaseTest {
    private val productsJson = """
        [
          {
            "code": "IIU", "salesCode": "II", "name": "Test IDE", "productFamilyName": "Test IDE",
            "link": "https://www.example.com/ide/", "description": "An IDE for tests",
            "tags": [{"id": "java", "name": "Java"}], "types": [{"id": "ide", "name": "IDE"}], "categories": ["IDE"],
            "intellijProductCode": "IU", "alternativeCodes": ["IU"], "forSale": true,
            "additionalLinks": [{"type": "link", "name": "Docs", "link": "https://www.example.com/docs/"}],
            "releases": [
              {
                "date": "2026-03-15", "type": "release", "printableReleaseType": null, "majorVersion": "2026.1",
                "version": "2026.1", "build": "261.100.10", "notesLink": "https://www.example.com/notes",
                "whatsnew": "<p>New</p>", "licenseRequired": true, "isSecurityCritical": false,
                "uninstallFeedbackLinks": {"linux": "https://www.example.com/feedback"},
                "patches": {"unix": [{"fromBuild": "253.10.1", "link": "https://www.example.com/patch.jar", "size": 1000, "checksumLink": "https://www.example.com/patch.sha256"}]},
                "downloads": {
                  "linux": {"link": "https://www.example.com/ide.tar.gz", "size": 100, "checksumLink": "https://www.example.com/ide.tar.gz.sha256"},
                  "macM1": {"link": "https://www.example.com/ide.dmg", "size": 200, "checksumLink": "https://www.example.com/ide.dmg.sha256"},
                  "futureType": {"link": "https://www.example.com/ide.new", "size": 300, "checksumLink": "https://www.example.com/ide.new.sha256"}
                }
              },
              {
                "date": "2025-12-01", "type": "eap", "majorVersion": "2026.1", "version": "2026.1 EAP", "build": "261.50.5",
                "downloads": {"windows": {"link": "https://www.example.com/ide.exe", "size": 400, "checksumLink": "https://www.example.com/ide.exe.sha256"}}
              }
            ]
          },
          {
            "code": "XNEW", "name": "Future Tool", "categories": ["Utilities"], "additionalLinks": [], "releases": []
          }
        ]
    """.trimIndent()

    private val client = KtorJetBrainsServiceClient(
        mockHttpClientBuilder(MockEngine { request ->
            when (request.url.encodedPath) {
                "/products" -> respond(productsJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respondError(HttpStatusCode.NotFound)
            }
        }),
        dataServicesUrl = "https://data.example.com",
    )

    @Test
    suspend fun downloadProductTypes() {
        val products = client.products().productsWithReleases
        assertEquals(2, products.size)

        val types = products.flatMap { product -> product.releases.flatMap { it.downloads.keys } }.toSet()
        assertEquals(setOf(LinuxDownloadType, MacM1DownloadType, WindowsDownloadType, UnknownDownloadType("futureType")), types)
    }

    @Test
    suspend fun productCodes() {
        val products = client.products().productsWithReleases
        assertEquals(JetBrainsProductCode.IntelliJIDEAUltimate, products[0].code)
        // a new product doesn't break the response
        assertEquals("XNEW", products[1].code.code)
        assertEquals(listOf(ProductReleaseType.Release, ProductReleaseType.EAP), products[0].releases.map { it.type })
    }
}
