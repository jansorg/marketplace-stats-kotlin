/*
 * Copyright (c) 2024 Joachim Ansorg.
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
            product.releases.flatMapTo(types) { it.downloads?.keys ?: emptyList() }
        }

        Assertions.assertFalse(types.isEmpty())
    }
}