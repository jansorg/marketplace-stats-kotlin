/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PluginPricingModel(val searchQueryValue: String) {
    @SerialName("PAID")
    Paid("PAID"),

    @SerialName("FREEMIUM")
    Freemium("FREEMIUM"),

    @SerialName("FREE")
    Free("FREE"),
}