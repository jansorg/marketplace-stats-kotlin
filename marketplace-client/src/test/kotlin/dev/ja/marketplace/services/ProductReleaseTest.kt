/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class ProductReleaseTest {
    @Test
    fun downloadProductTypes() = runBlocking {
        val client = KtorJetBrainsServiceClient()
        val products = client.products()

        val types = mutableSetOf<ProductDownloadType>()
        for (product in products.productsWithReleases) {
            product.releases.flatMapTo(types) { it.downloads.keys }
        }
        Assertions.assertFalse(types.isEmpty())

        val unknownProducts = products.productsWithReleases.filter { it.code is JetBrainsProductCode.UnknownCode }
        Assertions.assertTrue(unknownProducts.isEmpty()) {
            "All product codes must be supported. Found: $unknownProducts"
        }
    }
}