/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

enum class DownloadRequestDimension(val requestPathSegment: String) {
    // downloads grouped by product code, e.g. IC for IntelliJ IDEA Community
    ProductCode("product_code"),

    // downloads grouped by month
    Month("month"),

    // downloads grouped by day
    Day("day"),

    // downloads grouped by release of the plugin, the name of a group is the ID of the release
    Update("update"),
}