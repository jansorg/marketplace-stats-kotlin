/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.currency

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CurrencyInfo(
    @SerialName("iso")
    val currencyIsoId: String,
    @SerialName("symbol")
    val symbol: String,
    @SerialName("prefixSymbol")
    val prefixSymbol: Boolean,
)