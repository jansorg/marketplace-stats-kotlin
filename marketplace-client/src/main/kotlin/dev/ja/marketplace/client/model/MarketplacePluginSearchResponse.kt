/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MarketplacePluginSearchResponse(
    @SerialName("total")
    val totalResult: Int,
    @SerialName("correctedQuery")
    val correctedQuery: String? = null,
    @SerialName("plugins")
    val searchResult: List<MarketplacePluginSearchResultItem>,
)