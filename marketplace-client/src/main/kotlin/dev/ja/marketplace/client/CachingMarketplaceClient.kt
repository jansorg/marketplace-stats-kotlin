/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.plugins.*
import io.ktor.util.collections.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private data class CacheItem(
    val timestamp: Instant,
    val data: Any?,
    val exception: Throwable?,
) {
    init {
        assert(data != null || exception != null)
    }
}

/**
 * Caching client implementation, which keeps immutable data in memory and only fetches mutable data with new requests to the server.
 */
class CachingMarketplaceClient(
    private val delegate: MarketplaceClient
) : MarketplaceClient by delegate {
    private val unstableDataMonths = 2

    // cached data
    private val genericDataCache = ConcurrentMap<String, CacheItem>()
    private val cachedSalesInfo = ConcurrentMap<PluginId, Pair<YearMonthDayRange, List<PluginSale>>>()
    private val cachedTrialsInfo = ConcurrentMap<PluginId, Pair<YearMonthDayRange, List<PluginTrial>>>()

    fun reset() {
        genericDataCache.clear()
        cachedSalesInfo.clear()
        cachedTrialsInfo.clear()
    }

    override suspend fun userInfo(): UserInfo {
        return loadCached("userInfo", dataProvider = delegate::userInfo)
    }

    override suspend fun plugins(userId: UserId): List<PluginInfoSummary> {
        return loadCached("plugins.$userId") {
            delegate.plugins(userId)
        }
    }

    override suspend fun pluginInfo(id: PluginId): PluginInfo {
        return loadCached("pluginInfo.$id") {
            delegate.pluginInfo(id)
        }
    }

    override suspend fun pluginRating(id: PluginId): PluginRating {
        return loadCached("pluginRating.$id") {
            delegate.pluginRating(id)
        }
    }

    override suspend fun downloadsTotal(plugin: PluginId, vararg filters: DownloadFilter): Long {
        return loadCached("downloadsTotal.$plugin.${filters.filtersString()}") {
            delegate.downloadsTotal(plugin, *filters)
        }
    }

    override suspend fun downloads(
        plugin: PluginId,
        groupType: DownloadDimensionRequest,
        countType: DownloadCountType,
        vararg filters: DownloadFilter
    ): DownloadResponse {
        return loadCached("downloads.$plugin.$groupType.$countType.${filters.filtersString()}") {
            delegate.downloads(plugin, groupType, countType, *filters)
        }
    }

    override suspend fun downloadsMonthly(plugin: PluginId, countType: DownloadCountType): List<MonthlyDownload> {
        return loadCached("downloadsMonthly.$plugin.$countType") {
            delegate.downloadsMonthly(plugin, countType)
        }
    }

    override suspend fun downloadsDaily(plugin: PluginId, countType: DownloadCountType): List<DailyDownload> {
        return loadCached("downloadsDaily.$plugin.$countType") {
            delegate.downloadsDaily(plugin, countType)
        }
    }

    override suspend fun downloadsByProduct(plugin: PluginId, countType: DownloadCountType): List<ProductDownload> {
        return loadCached("downloadsByProduct.$plugin.$countType") {
            delegate.downloadsByProduct(plugin, countType)
        }
    }

    override suspend fun trialsInfo(plugin: PluginId): List<PluginTrial> {
        return loadHistoricPluginData(plugin, "trialsInfo", cachedTrialsInfo, delegate::trialsInfo)
    }

    override suspend fun salesInfo(plugin: PluginId): List<PluginSale> {
        return loadHistoricPluginData(plugin, "salesInfo", cachedSalesInfo, delegate::salesInfo)
    }

    override suspend fun licenseInfo(plugin: PluginId): SalesWithLicensesInfo {
        return loadCached("licenseInfo") {
            val sales = salesInfo(plugin) // cached sales, not using delegate.licenseInfo because it would fetch sales again
            SalesWithLicensesInfo(sales, LicenseInfo.createFrom(sales))
        }
    }

    override suspend fun compatibleProducts(plugin: PluginId): List<JetBrainsProductId> {
        return loadCached("compatibleProducts.$plugin") {
            delegate.compatibleProducts(plugin)
        }
    }

    override suspend fun volumeDiscounts(plugin: PluginId): VolumeDiscountResponse {
        return loadCached("volumeDiscounts.$plugin") {
            delegate.volumeDiscounts(plugin)
        }
    }

    override suspend fun marketplacePluginInfo(plugin: PluginId, fullInfo: Boolean): MarketplacePluginInfo {
        return loadCached("marketplacePluginInfo.$plugin.$fullInfo") {
            delegate.marketplacePluginInfo(plugin, fullInfo)
        }
    }

    override suspend fun comments(plugin: PluginId): List<PluginComment> {
        return loadCached("comments.$plugin") {
            delegate.comments(plugin)
        }
    }

    override suspend fun priceInfo(plugin: PluginId, isoCountryCode: String): PluginPriceInfo {
        // pricing data is expensive to fetch and is expected to change very seldom
        return loadCached("priceInfo.$plugin.$isoCountryCode", cacheDuration = 24.hours) {
            delegate.priceInfo(plugin, isoCountryCode)
        }
    }

    override suspend fun channels(plugin: PluginId): List<PluginChannel> {
        return loadCached("channels.$plugin") {
            delegate.channels(plugin)
        }
    }

    override suspend fun releases(plugin: PluginId, channel: PluginChannel, size: Int, page: Int): List<PluginReleaseInfo> {
        return loadCached("releases.$plugin.$channel.$size.$page") {
            delegate.releases(plugin, channel, size, page)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> loadCached(key: String, cacheDuration: Duration = 30.minutes, dataProvider: suspend () -> T): T {
        val now = Clock.System.now()

        val cachedResult = genericDataCache[key]?.takeIf { it.timestamp + cacheDuration > now }
        val finalResult = when {
            cachedResult != null -> cachedResult
            else -> {
                val newCacheItem = try {
                    CacheItem(now, dataProvider(), null)
                } catch (e: ClientRequestException) {
                    val status = e.response.status.value
                    when {
                        status < 500 -> CacheItem(now, null, e)
                        else -> throw e
                    }
                }

                genericDataCache[key] = newCacheItem
                newCacheItem
            }
        }

        if (finalResult.data != null) {
            return finalResult.data as T
        }

        throw finalResult.exception!!
    }

    private suspend fun <T> loadHistoricPluginData(
        plugin: PluginId,
        cacheKey: String,
        cacheMap: MutableMap<PluginId, Pair<YearMonthDayRange, List<T>>>,
        dataLoader: suspend (PluginId, YearMonthDayRange) -> List<T>
    ): List<T> {
        val now = YearMonthDay.now()
        val stableEndDate = now.add(0, -unstableDataMonths, 0)
        val unstableStartDate = stableEndDate.add(0, 0, 1)
        val unstableDataRange = Marketplace.Birthday.rangeTo(stableEndDate)

        // The current data is only cached for a short duration (30 minutes).
        // It's merged to the stable data, which is cached for a longer period (1 day).
        val unstableData = loadCached("$cacheKey.$plugin") {
            dataLoader(plugin, unstableStartDate.rangeTo(now))
        }

        val cachedStableData = cacheMap[plugin]
        if (cachedStableData != null && cachedStableData.first == unstableDataRange) {
            return cachedStableData.second + unstableData
        }

        // add fresh data to the cache
        val newStableData = unstableDataRange to dataLoader(plugin, unstableDataRange)
        cacheMap[plugin] = newStableData
        return newStableData.second + unstableData
    }

    private fun Array<out DownloadFilter>.filtersString(): String {
        return map(DownloadFilter::toString).sorted().joinToString(",")
    }
}
