/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

data class Products(val productsWithReleases: List<ProductWithReleases>) {
    fun getProduct(code: JetBrainsProductCode): ProductWithReleases? {
        return productsWithReleases.firstOrNull { it.code == code }
    }
}