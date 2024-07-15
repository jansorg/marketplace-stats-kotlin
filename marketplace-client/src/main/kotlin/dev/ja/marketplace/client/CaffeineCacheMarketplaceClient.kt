/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import dev.hsbrysk.caffeine.buildCoroutine
import io.ktor.client.plugins.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class CaffeineCacheMarketplaceClient(
    apiKey: String,
    apiHost: String = "plugins.jetbrains.com",
    apiPath: String = "api",
    logLevel: ClientLogLevel = ClientLogLevel.None,
    enableHttpCaching: Boolean = true,
    private val unstableHistoricDataDays: Int = 45
) : KtorMarketplaceClient(apiKey, apiHost, apiPath, logLevel, enableHttpCaching), CacheAware {
    private val cache = Caffeine.newBuilder()
        .expireAfter(CacheExpiry())
        .maximumSize(1_000)
        .buildCoroutine()

    override fun invalidateCache() {
        cache.synchronous().invalidateAll()
    }

    override suspend fun userInfo(): UserInfo {
        return loadCached("userInfo", 1.days) {
            super.userInfo()
        }
    }

    override suspend fun plugins(userId: UserId): List<PluginInfoSummary> {
        return loadCached("plugins.$userId", 1.days) {
            super.plugins(userId)
        }
    }

    override suspend fun pluginInfo(plugin: PluginId): PluginInfo {
        return loadCached("pluginInfo.$plugin", 12.hours) {
            super.pluginInfo(plugin)
        }
    }

    override suspend fun pluginRating(id: PluginId): PluginRating {
        return loadCached("pluginRating.$id", 30.minutes) {
            super.pluginRating(id)
        }
    }

    override suspend fun licenseInfo(plugin: PluginId): SalesWithLicensesInfo {
        return loadCached("licenseInfo.$plugin", 30.minutes) {
            super.licenseInfo(plugin)
        }
    }

    override suspend fun downloadsTotal(plugin: PluginId, vararg filters: DownloadFilter): Long {
        val filterString = filters.map(DownloadFilter::toString).sorted().joinToString(",")
        return loadCached("downloadsTotal.$plugin.$filterString", 30.minutes) {
            super.downloadsTotal(plugin, *filters)
        }
    }

    override suspend fun downloads(
        plugin: PluginId,
        groupType: DownloadDimensionRequest,
        countType: DownloadCountType,
        vararg filters: DownloadFilter
    ): DownloadResponse {
        val filterString = filters.map(DownloadFilter::toString).sorted().joinToString(",")
        return loadCached("downloads.$plugin.$groupType.$countType.$filterString", 30.minutes) {
            super.downloads(plugin, groupType, countType, *filters)
        }
    }

    override suspend fun downloadsMonthly(plugin: PluginId, countType: DownloadCountType): List<MonthlyDownload> {
        return loadCached("downloadsMonthly.$plugin.$countType", 30.minutes) {
            super.downloadsMonthly(plugin, countType)
        }
    }

    override suspend fun downloadsDaily(plugin: PluginId, countType: DownloadCountType): List<DailyDownload> {
        return loadCached("downloadsDaily.$plugin.$countType", 30.minutes) {
            super.downloadsDaily(plugin, countType)
        }
    }

    override suspend fun downloadsByProduct(plugin: PluginId, countType: DownloadCountType): List<ProductDownload> {
        return loadCached("downloadsByProduct.$plugin.$countType", 30.minutes) {
            super.downloadsByProduct(plugin, countType)
        }
    }

    override suspend fun compatibleProducts(plugin: PluginId): List<JetBrainsProductId> {
        return loadCached("compatibleProducts.$plugin", 1.days) {
            super.compatibleProducts(plugin)
        }
    }

    override suspend fun volumeDiscounts(plugin: PluginId): VolumeDiscountResponse {
        return loadCached("volumeDiscount.$plugin", 1.days) {
            super.volumeDiscounts(plugin)
        }
    }

    override suspend fun marketplacePluginInfo(plugin: PluginId, fullInfo: Boolean): MarketplacePluginInfo {
        return loadCached("marketplacePluginInfo.$plugin.$fullInfo", 1.days) {
            super.marketplacePluginInfo(plugin, fullInfo)
        }
    }

    override suspend fun marketplaceSearchPlugins(request: MarketplacePluginSearchRequest): MarketplacePluginSearchResponse {
        return loadCached("marketplaceSearchPlugins.$request", 30.minutes) {
            super.marketplaceSearchPlugins(request)
        }
    }

    override suspend fun marketplaceSearchPluginsPaging(
        request: MarketplacePluginSearchRequest,
        pageSize: Int
    ): List<MarketplacePluginSearchResultItem> {
        return loadCached("marketplaceSearchPluginsPaging.$request.$pageSize", 30.minutes) {
            super.marketplaceSearchPluginsPaging(request, pageSize)
        }
    }

    override suspend fun comments(plugin: PluginId): List<PluginComment> {
        return loadCached("comments.$plugin", 30.minutes) {
            super.comments(plugin)
        }
    }

    override suspend fun channels(plugin: PluginId): List<PluginChannel> {
        return loadCached("channels.$plugin", 1.days) {
            super.channels(plugin)
        }
    }

    override suspend fun releases(plugin: PluginId, channel: PluginChannel, size: Int, page: Int): List<PluginReleaseInfo> {
        return loadCached("releases.$plugin.$channel.$size.$page", 1.days) {
            super.releases(plugin, channel, size, page)
        }
    }

    override suspend fun priceInfo(plugin: PluginId, isoCountryCode: String): PluginPriceInfo {
        return loadCached("priceInfo.$plugin.$isoCountryCode", 7.days) {
            super.priceInfo(plugin, isoCountryCode)
        }
    }

    override suspend fun loadSalesInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginSale> {
        val cacheDuration = when {
            range.end.daysUntil(YearMonthDay.now()) > unstableHistoricDataDays -> 7.days
            else -> 30.minutes
        }

        return loadCached("loadSalesInfo.$plugin.$range", cacheDuration) {
            super.loadSalesInfo(plugin, range)
        }
    }

    override suspend fun loadTrialsInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginTrial> {
        val cacheDuration = when {
            range.end.daysUntil(YearMonthDay.now()) > unstableHistoricDataDays -> 7.days
            else -> 30.minutes
        }

        return loadCached("loadTrialsInfo.$plugin.$range", cacheDuration) {
            super.loadTrialsInfo(plugin, range)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> loadCached(key: String, expireAfterCreate: Duration, loader: suspend () -> T): T {
        val cachedValue = cache.get(CacheKey(key, expireAfterCreate)) {
            try {
                CacheValue(loader(), null)
            } catch (e: ClientRequestException) {
                CacheValue(null, e)
            }
        } ?: throw IllegalStateException("Failed to compute cache value for $key")

        if (cachedValue.result != null) {
            return cachedValue.result as T
        }

        throw cachedValue.exception!!
    }
}

private data class CacheKey(val key: String, val expireAfterWrite: Duration)

private data class CacheValue(val result: Any?, val exception: ClientRequestException?) {
    init {
        require(result != null || exception != null)
    }
}

private class CacheExpiry : Expiry<CacheKey, CacheValue> {
    override fun expireAfterCreate(key: CacheKey, value: CacheValue, currentTime: Long): Long {
        return key.expireAfterWrite.inWholeNanoseconds
    }

    override fun expireAfterUpdate(key: CacheKey, value: CacheValue, currentTime: Long, currentDuration: Long): Long {
        return key.expireAfterWrite.inWholeNanoseconds
    }

    override fun expireAfterRead(key: CacheKey, value: CacheValue, currentTime: Long, currentDuration: Long): Long {
        return Long.MAX_VALUE
    }
}
