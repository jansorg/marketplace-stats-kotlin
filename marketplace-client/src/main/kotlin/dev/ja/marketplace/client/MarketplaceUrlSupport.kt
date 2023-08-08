/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

interface MarketplaceUrlSupport {
    fun assetUrl(path: String): String
}