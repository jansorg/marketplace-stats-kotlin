/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The monetization settings of a paid or freemium plugin.
 */
@Serializable
data class PluginMonetization(
    @SerialName("productCode")
    val productCode: String,
    /** The pricing model, e.g. `PAID` or `FREEMIUM`. It's a string, so that new models don't break the client. */
    @SerialName("pricingModel")
    val pricingModel: String,
    /** The confirmed requirements of the monetization, e.g. `OBFUSCATE`. */
    @SerialName("checkboxes")
    val confirmedRequirements: List<String> = emptyList(),
) {
    val pricingModelValue: PluginPricingModel?
        get() = PluginPricingModel.entries.firstOrNull { it.searchQueryValue == pricingModel }
}
