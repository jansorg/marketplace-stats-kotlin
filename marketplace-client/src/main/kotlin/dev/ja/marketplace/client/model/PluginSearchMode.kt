/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

/**
 * How [dev.ja.marketplace.client.MarketplaceClientPublic.marketplacePluginsSearch] retrieves the results of a search.
 */
enum class PluginSearchMode {
    /**
     * The search of the Marketplace: the results are in the requested order, but limited to the first
     * [dev.ja.marketplace.client.Marketplace.MAX_SEARCH_RESULT_WINDOW] results of the query.
     */
    Marketplace,

    /**
     * All results, also beyond the first [dev.ja.marketplace.client.Marketplace.MAX_SEARCH_RESULT_WINDOW] results of the query.
     *
     * If the query has fewer results than the window, this is the same as [Marketplace].
     * Otherwise, the results are collected by disjoint queries, one per tag of the Marketplace
     * (with the tags of the previous queries excluded) and one for plugins without any of these tags.
     * The requested order is ignored in this case: the results are in the order of these queries,
     * and each query is in the requested order. `offset` and `maxResults` of the request apply to this order.
     */
    AnySizeWorkaround,
}
