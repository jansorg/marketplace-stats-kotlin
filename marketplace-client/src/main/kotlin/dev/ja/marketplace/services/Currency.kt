/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Currency(
    @SerialName("iso")
    val isoCode: String,
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
}
