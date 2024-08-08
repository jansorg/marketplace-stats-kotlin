/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import dev.hsbrysk.caffeine.buildCoroutine
import io.ktor.client.*
import io.ktor.client.plugins.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class CaffeineCacheMarketplaceClient(
    apiHost: String = Marketplace.HOSTNAME,
    apiPath: String = Marketplace.API_PATH,
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2),
    httpClient: HttpClient,
    private val unstableHistoricDataDays: Int = 45,
) : KtorMarketplaceClient(apiHost, apiPath, dispatcher, httpClient), CacheAware {
    constructor(
        apiKey: String,
        apiHost: String = Marketplace.HOSTNAME,
        apiPath: String = Marketplace.API_PATH,
        logLevel: ClientLogLevel = ClientLogLevel.None,
        enableHttpCaching: Boolean = false,
        dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2),
    ) : this(
        apiHost,
        apiPath,
        dispatcher,
        KtorHttpClientFactory.createHttpClient(apiHost, apiKey, logLevel = logLevel, enableHttpCaching = enableHttpCaching)
    )

    private val frequentlyModifiedCacheDuration = 30.minutes
    private val rarelyModifiedCacheDuration = 1.days
    private val mostlyStaticCacheDuration = 7.days

    private val cache = Caffeine.newBuilder()
        .expireAfter(CacheExpiry())
        .maximumSize(10_000)
        .buildCoroutine()

    override fun invalidateCache() {
        cache.synchronous().invalidateAll()
    }

    override suspend fun userInfo(): UserInfo {
        return loadCached("userInfo", rarelyModifiedCacheDuration) {
            super.userInfo()
        }
    }

    override suspend fun plugins(userId: UserId, family: List<ProductFamily>?, page: Int, maxResults: Int?): List<PluginInfoSummary> {
        val families = family?.joinToString(",", transform = ProductFamily::jsonId) ?: ""
        return loadCached("plugins.$userId.$families.$page.${maxResults ?: -1}", rarelyModifiedCacheDuration) {
            super.plugins(userId, family, page, maxResults)
        }
    }

    override suspend fun pluginInfo(plugin: PluginId): PluginInfo {
        return loadCached("pluginInfo.$plugin", rarelyModifiedCacheDuration) {
            super.pluginInfo(plugin)
        }
    }

    override suspend fun pluginRating(id: PluginId): PluginRating {
        return loadCached("pluginRating.$id", frequentlyModifiedCacheDuration) {
            super.pluginRating(id)
        }
    }

    override suspend fun licenseInfo(plugin: PluginId): SalesWithLicensesInfo {
        return loadCached("licenseInfo.$plugin", frequentlyModifiedCacheDuration) {
            super.licenseInfo(plugin)
        }
    }

    override suspend fun downloadsTotal(plugin: PluginId, vararg filters: DownloadFilter): Long {
        val filterString = filters.map(DownloadFilter::toString).sorted().joinToString(",")
        return loadCached("downloadsTotal.$plugin.$filterString", frequentlyModifiedCacheDuration) {
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
        return loadCached("downloads.$plugin.$groupType.$countType.$filterString", frequentlyModifiedCacheDuration) {
            super.downloads(plugin, groupType, countType, *filters)
        }
    }

    override suspend fun downloadsMonthly(plugin: PluginId, countType: DownloadCountType): List<MonthlyDownload> {
        return loadCached("downloadsMonthly.$plugin.$countType", frequentlyModifiedCacheDuration) {
            super.downloadsMonthly(plugin, countType)
        }
    }

    override suspend fun downloadsDaily(plugin: PluginId, countType: DownloadCountType): List<DailyDownload> {
        return loadCached("downloadsDaily.$plugin.$countType", frequentlyModifiedCacheDuration) {
            super.downloadsDaily(plugin, countType)
        }
    }

    override suspend fun downloadsByProduct(plugin: PluginId, countType: DownloadCountType): List<ProductDownload> {
        return loadCached("downloadsByProduct.$plugin.$countType", frequentlyModifiedCacheDuration) {
            super.downloadsByProduct(plugin, countType)
        }
    }

    override suspend fun compatibleProducts(plugin: PluginId): List<JetBrainsProductId> {
        return loadCached("compatibleProducts.$plugin", rarelyModifiedCacheDuration) {
            super.compatibleProducts(plugin)
        }
    }

    override suspend fun volumeDiscounts(plugin: PluginId): VolumeDiscountResponse {
        return loadCached("volumeDiscount.$plugin", rarelyModifiedCacheDuration) {
            super.volumeDiscounts(plugin)
        }
    }

    override suspend fun marketplacePluginInfo(plugin: PluginId, fullInfo: Boolean): MarketplacePluginInfo {
        return loadCached("marketplacePluginInfo.$plugin.$fullInfo", rarelyModifiedCacheDuration) {
            super.marketplacePluginInfo(plugin, fullInfo)
        }
    }

    override suspend fun marketplacePluginsSearchSinglePage(request: MarketplacePluginSearchRequest): MarketplacePluginSearchResponse {
        return loadCached("marketplaceSearchPluginsPage.$request", frequentlyModifiedCacheDuration) {
            super.marketplacePluginsSearchSinglePage(request)
        }
    }

    override suspend fun marketplacePluginsSearch(
        request: MarketplacePluginSearchRequest,
        pageSize: Int
    ): List<MarketplacePluginSearchResultItem> {
        return loadCached("marketplaceSearchPlugins.$request.$pageSize", frequentlyModifiedCacheDuration) {
            super.marketplacePluginsSearch(request, pageSize)
        }
    }

    override suspend fun reviewComments(plugin: PluginId): List<PluginReviewComment> {
        return loadCached("reviewComments.$plugin", frequentlyModifiedCacheDuration) {
            super.reviewComments(plugin)
        }
    }

    override suspend fun reviewReplies(plugin: PluginId): List<PluginReviewComment> {
        return loadCached("reviewReplies.$plugin", frequentlyModifiedCacheDuration) {
            super.reviewReplies(plugin)
        }
    }

    override suspend fun channels(plugin: PluginId): List<PluginChannel> {
        return loadCached("channels.$plugin", rarelyModifiedCacheDuration) {
            super.channels(plugin)
        }
    }

    override suspend fun releasesSinglePage(plugin: PluginId, channel: PluginChannel, size: Int, page: Int): List<PluginReleaseInfo> {
        return loadCached("releases.$plugin.$channel.$size.$page", rarelyModifiedCacheDuration) {
            super.releasesSinglePage(plugin, channel, size, page)
        }
    }

    override suspend fun priceInfo(plugin: PluginId, isoCountryCode: String): PluginPriceInfo {
        return loadCached("priceInfo.$plugin.$isoCountryCode", mostlyStaticCacheDuration) {
            super.priceInfo(plugin, isoCountryCode)
        }
    }

    override suspend fun loadSalesInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginSale> {
        val cacheDuration = when {
            abs(range.start daysUntil YearMonthDay.now()) > unstableHistoricDataDays -> mostlyStaticCacheDuration
            else -> frequentlyModifiedCacheDuration
        }

        return loadCached("loadSalesInfo.$plugin.$range", cacheDuration) {
            super.loadSalesInfo(plugin, range)
        }
    }

    override suspend fun loadTrialsInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginTrial> {
        val cacheDuration = when {
            abs(range.start daysUntil YearMonthDay.now()) > unstableHistoricDataDays -> mostlyStaticCacheDuration
            else -> frequentlyModifiedCacheDuration
        }

        return loadCached("loadTrialsInfo.$plugin.$range", cacheDuration) {
            super.loadTrialsInfo(plugin, range)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> loadCached(key: String, expireAfterCreate: Duration, loader: suspend () -> T): T {
        val cachedValue = cache.get(key) {
            try {
                CacheValue(expireAfterCreate, loader(), null)
            } catch (e: ClientRequestException) {
                CacheValue(expireAfterCreate, null, e)
            }
        } ?: throw IllegalStateException("Failed to compute cache value for $key")

        if (cachedValue.result != null) {
            return cachedValue.result as T
        }

        throw cachedValue.exception!!
    }
}

private data class CacheValue(val expireAfterWrite: Duration, val result: Any?, val exception: ClientRequestException?) {
    init {
        require(result != null || exception != null)
    }
}

private class CacheExpiry : Expiry<String, CacheValue> {
    override fun expireAfterCreate(key: String, value: CacheValue, currentTime: Long): Long {
        return value.expireAfterWrite.inWholeNanoseconds
    }

    override fun expireAfterUpdate(key: String, value: CacheValue, currentTime: Long, currentDuration: Long): Long {
        return currentDuration
    }

    override fun expireAfterRead(key: String, value: CacheValue, currentTime: Long, currentDuration: Long): Long {
        return currentDuration
    }
}
