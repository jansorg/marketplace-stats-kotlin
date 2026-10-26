/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import dev.ja.marketplace.client.persistentCache.PersistentCacheStore
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.days

class CachingJetBrainsServiceClientTest {
    @TempDir
    lateinit var tempDir: Path

    private val requests = AtomicInteger()

    private val release = ProductRelease(
        date = LocalDate(2025, 4, 16),
        type = ProductReleaseType.Release,
        majorVersion = "2025.1",
        version = "2025.1",
        build = BuildNumber.of("251.23774.435"),
        patches = mapOf(ProductPatchType.UNIX to listOf(ProductPatchRelease("243.1", "https://example.com/patch", 100, "https://example.com/sum"))),
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
    }

    @Test
    suspend fun productsAndReleasesAreStored() {
        val store = PersistentCacheStore(tempDir)
        val client = CachingJetBrainsServiceClient(delegate, store)
        assertEquals(listOf(release), client.productReleases(JetBrainsProductCode.IntelliJIDEAUltimate, ProductReleaseType.Release))
        assertEquals(listOf(product), client.products().productsWithReleases)
        // cached in memory
        client.products()
        assertEquals(2, requests.get())

        // the files are written shortly after the requests
        repeat(50) {
            if (!store.exists("jetbrains-products") || !store.exists("jetbrains-product-releases")) delay(100)
        }

        // a new client, e.g. after a restart, reads the stored data
        val restarted = CachingJetBrainsServiceClient(delegate, PersistentCacheStore(tempDir))
        assertEquals(listOf(release), restarted.productReleases(JetBrainsProductCode.IntelliJIDEAUltimate, ProductReleaseType.Release))
        assertEquals(listOf(product), restarted.products().productsWithReleases)
        assertEquals(2, requests.get())

        restarted.invalidateCache()
        restarted.products()
        assertEquals(3, requests.get())
    }

    @Test
    suspend fun withoutStore() {
        val client = CachingJetBrainsServiceClient(delegate, store = null, maxAge = 1.days)
        client.products()
        client.products()
        assertEquals(1, requests.get())
    }
}
