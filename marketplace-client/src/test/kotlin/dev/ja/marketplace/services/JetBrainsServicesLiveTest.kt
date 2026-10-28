/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The products of the data services of JetBrains, loaded from their server. Only run by `./gradlew liveTest`.
 */
@Tag("live")
class JetBrainsServicesLiveTest {
    @Test
    suspend fun downloadProductTypes() {
        KtorJetBrainsServiceClient().use { client ->
            val products = client.products().productsWithReleases
            val types = products.flatMap { product -> product.releases.flatMap { it.downloads.keys } }.toSet()
            assertFalse(types.isEmpty())

            val unknownProducts = products.filter { it.code is JetBrainsProductCode.UnknownCode }
            assertTrue(unknownProducts.isEmpty()) { "All product codes must be supported. Found: $unknownProducts" }
        }
    }

    @Test
    suspend fun countries() {
        KtorJetBrainsServiceClient().use { client ->
            assertTrue(client.countries().countries.any { it.country.isoCode == "US" })
        }
    }
}
