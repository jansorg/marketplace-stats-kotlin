/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.util.collections.*

/**
 * Caching client implementation, which keeps immutable data in memory and only fetches mutable data with new requests to the server.
 */
class CachingMarketplaceClient(
    private val delegate: MarketplaceClient
) : MarketplaceClient by delegate {
    private val unstableDataMonths = 2
    private val cachedSalesInfo = ConcurrentMap<PluginId, Pair<YearMonthDayRange, List<PluginSale>>>()
    private val cachedTrialsInfo = ConcurrentMap<PluginId, Pair<YearMonthDayRange, List<PluginTrial>>>()

    override suspend fun trialsInfo(plugin: PluginId): List<PluginTrial> {
        return load(plugin, cachedTrialsInfo, delegate::trialsInfo)
    }

    override suspend fun salesInfo(plugin: PluginId): List<PluginSale> {
        return load(plugin, cachedSalesInfo, delegate::salesInfo)
    }

    private suspend fun <T> load(
        plugin: PluginId,
        cacheMap: MutableMap<PluginId, Pair<YearMonthDayRange, List<T>>>,
        dataLoader: suspend (PluginId, YearMonthDayRange) -> List<T>
    ): List<T> {
        val now = YearMonthDay.now()
        val stableEndDate = now.add(0, -unstableDataMonths, 0)
        val unstableStartDate = stableEndDate.add(0, 0, 1)
        val unstableDataRange = Marketplace.Birthday.rangeTo(stableEndDate)

        var cachedStableData = cacheMap[plugin]?.takeIf { it.first == unstableDataRange }
        if (cachedStableData == null) {
            cachedStableData = unstableDataRange to dataLoader(plugin, unstableDataRange)
            cacheMap[plugin] = cachedStableData
        }
        return cachedStableData.second + dataLoader(plugin, unstableStartDate.rangeTo(now))
    }

}