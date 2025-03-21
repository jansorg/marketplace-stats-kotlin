/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApplicationConfig(
    @SerialName("apiKey")
    val marketplaceApiKey: String,
    @SerialName("displayedCurrency")
    val displayedCurrency: String? = null,
    @SerialName("showResellerCharges")
    val showResellerCharges: Boolean = false,
)