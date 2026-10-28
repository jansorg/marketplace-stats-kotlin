/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

/**
 * Client of the services of JetBrains, e.g. the products and their releases. [close] closes its HTTP client.
 */
interface JetBrainsServiceClient : AutoCloseable {
    suspend fun countries(): Countries

    suspend fun products(): Products

    /**
     * @return The releases of a product, sorted by date in descending order.
     */
    suspend fun productReleases(product: JetBrainsProductCode, type: ProductReleaseType = ProductReleaseType.Release): List<ProductRelease>

    companion object {
        /** The base URL of the account services of JetBrains, e.g. the countries. */
        const val ACCOUNT_SERVICES_URL: String = "https://account.jetbrains.com"

        /** The base URL of the data services of JetBrains, e.g. the products and their releases. */
        const val DATA_SERVICES_URL: String = "https://data.services.jetbrains.com"
    }
}
