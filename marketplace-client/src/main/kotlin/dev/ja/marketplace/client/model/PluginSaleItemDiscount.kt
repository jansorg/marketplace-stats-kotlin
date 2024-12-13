/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginSaleItemDiscount(
    @SerialName("description")
    val description: String,
    @SerialName("percent")
    val percent: Double? = null,
) {
    val isContinuityDiscount: Boolean
        get() {
            return description.contains(" continuity discount")
        }

    val isResellerDiscount: Boolean
        get() {
            return description.contains("Reseller discount")
        }
}