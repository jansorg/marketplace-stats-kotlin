/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

interface JetBrainsServiceClient {
    suspend fun countries(): Countries

    suspend fun products(): Products

    /**
     * @return The releases of a product, sorted by date in descending order.
     */
    suspend fun productReleases(product: JetBrainsProductCode, type: ProductReleaseType = ProductReleaseType.Release): List<ProductRelease>
}