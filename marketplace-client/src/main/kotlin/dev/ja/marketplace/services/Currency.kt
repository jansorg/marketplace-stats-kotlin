/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

typealias CurrencyIsoCode = String

@Serializable
data class Currency(
    @SerialName("iso")
    val isoCode: CurrencyIsoCode,
    @SerialName("symbol")
    val symbol: String,
    @SerialName("prefixSymbol")
    val prefixSymbol: Boolean,
) : Comparable<Currency> {
    override fun compareTo(other: Currency): Int {
        return isoCode.compareTo(isoCode)
    }

    override fun toString(): String {
        return isoCode
    }

    fun hasCode(currencyCode: String): Boolean {
        return this.isoCode == currencyCode
    }
}
