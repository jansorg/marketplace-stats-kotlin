/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.currency.CurrencyInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
)