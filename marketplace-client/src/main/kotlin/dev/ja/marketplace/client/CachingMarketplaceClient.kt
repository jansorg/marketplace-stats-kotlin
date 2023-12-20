/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.util.collections.*

/**
 * Caching client implementation, which keeps immutable data in memory and only fetches mutable data with new requests to the server.
 */
class CachingMarketplaceClient(private val delegate: MarketplaceClient) : MarketplaceClient by delegate {
    private val cachedSalesInfo = ConcurrentMap<PluginId, Pair<YearMonthDayRange, List<PluginSale>>>()

    override suspend fun salesInfo(plugin: PluginId): List<PluginSale> {
        return wrapIsRefreshNeeded { stableDataRange, unstableDataRange ->
            var cachedStableData = cachedSalesInfo[plugin]?.takeIf { it.first == stableDataRange }
            if (cachedStableData == null) {
                cachedStableData = stableDataRange to delegate.salesInfo(plugin, stableDataRange)
                cachedSalesInfo[plugin] = cachedStableData
            }

            val newData = delegate.salesInfo(plugin, unstableDataRange)

            return@wrapIsRefreshNeeded cachedStableData.second + newData
        }
    }

    private suspend fun <T> wrapIsRefreshNeeded(
        block: suspend (stableDataRange: YearMonthDayRange, unstableDataRange: YearMonthDayRange) -> T
    ): T {
        val now = YearMonthDay.now()
        val oldDataEnd = now.add(0, -6, 0)
        val newDataStart = oldDataEnd.add(0, 0, 1)
        return block(Marketplace.Birthday.rangeTo(oldDataEnd), newDataStart.rangeTo(now))
    }
}