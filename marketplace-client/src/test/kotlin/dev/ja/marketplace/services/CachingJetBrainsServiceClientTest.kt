/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class CachingJetBrainsServiceClientTest {
    private val requests = AtomicInteger()
    private var closed = false

    private val release = ProductRelease(
        date = LocalDate(2025, 4, 16),
        type = ProductReleaseType.Release,
        majorVersion = "2025.1",
        version = "2025.1",
        build = BuildNumber.of("251.23774.435"),
        patches = mapOf("unix" to listOf(ProductPatchRelease("243.1", "https://example.com/patch", 100, "https://example.com/sum"))),
        downloads = mapOf(LinuxDownloadType to ProductReleaseDownload("https://example.com/linux", "https://example.com/linux.sha", 1000)),
    )

    private val product = ProductWithReleases(
        code = JetBrainsProductCode.IntelliJIDEAUltimate,
        name = "IntelliJ IDEA",
        categories = listOf(IdeCategory),
        releases = listOf(release),
        additionalLinks = null,
    )

    private val delegate = object : JetBrainsServiceClient {
        override suspend fun countries(): Countries = Countries(emptyList())

        override suspend fun products(): Products {
            requests.incrementAndGet()
            return Products(listOf(product))
        }

        override suspend fun productReleases(product: JetBrainsProductCode, type: ProductReleaseType): List<ProductRelease> {
            requests.incrementAndGet()
            return listOf(release)
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    suspend fun productsAndReleasesAreCachedInMemory() {
        val client = CachingJetBrainsServiceClient(delegate)
        assertEquals(listOf(release), client.productReleases(JetBrainsProductCode.IntelliJIDEAUltimate, ProductReleaseType.Release))
        assertEquals(listOf(product), client.products().productsWithReleases)
        client.productReleases(JetBrainsProductCode.IntelliJIDEAUltimate, ProductReleaseType.Release)
        client.products()
        assertEquals(2, requests.get())

        client.invalidateCache()
        client.products()
        assertEquals(3, requests.get())
    }

    @Test
    suspend fun cachedDataExpires() {
        val client = CachingJetBrainsServiceClient(delegate, maxAge = 100.milliseconds)
        client.products()
        delay(300)
        client.products()
        assertEquals(2, requests.get())
    }

    @Test
    fun closeClosesTheDelegate() {
        CachingJetBrainsServiceClient(delegate).close()
        assertTrue(closed)
    }
}
