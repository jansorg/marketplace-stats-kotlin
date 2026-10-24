/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.currency.CurrencyInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PluginPriceInfo(
    @SerialName("shopBuyUrl")
    val shopBuyUrl: String,
    @SerialName("shopQuoteUrl")
    val shopQuoteUrl: String,
    @SerialName("currency")
    val currency: CurrencyInfo,
    @SerialName("pluginInfo")
    val prices: PluginPriceInfoByType,
    /**
     * The products, which include the plugin, e.g. a bundle. The API returned only empty lists so far,
     * the elements are kept as JSON until their format is known.
     */
    @SerialName("includedIn")
    val includedIn: List<JsonElement> = emptyList(),
)