/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CountryWithCurrency(
    @SerialName("country")
    val country: Country,
    @SerialName("currency")
    val currency: Currency
)
