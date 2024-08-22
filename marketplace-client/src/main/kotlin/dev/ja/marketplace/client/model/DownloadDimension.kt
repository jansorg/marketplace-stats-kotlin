/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DownloadDimension {
    // total downloads of a plugin
    @SerialName("plugin")
    Plugin,

    // downloads grouped by product code, e.g. IC for IntelliJ IDEA Community
    @SerialName("product_code")
    ProductCode,

    // downloads grouped by month
    @SerialName("month")
    Month,

    // downloads grouped by day
    @SerialName("day")
    Day,
}