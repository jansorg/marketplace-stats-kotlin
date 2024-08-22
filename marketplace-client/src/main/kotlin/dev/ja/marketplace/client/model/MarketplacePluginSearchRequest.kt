/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.MarketplaceTag
import dev.ja.marketplace.client.PluginTagName

data class MarketplacePluginSearchRequest(
    /* `null` means all results */
    val maxResults: Int? = null,
    val offset: Int = 0,
    val queryFilter: String? = null,
    val orderBy: PluginSearchOrderBy? = PluginSearchOrderBy.Relevance,
    val products: List<PluginSearchProductId>? = null,
    val requiredTags: List<PluginTagName> = emptyList(),
    val excludeTags: List<PluginTagName> = listOf(MarketplaceTag.Theme),
    val pricingModels: List<PluginPricingModel>? = null,
    val shouldHaveSource: Boolean? = null,
    val isFeaturedSearch: Boolean? = null,
)