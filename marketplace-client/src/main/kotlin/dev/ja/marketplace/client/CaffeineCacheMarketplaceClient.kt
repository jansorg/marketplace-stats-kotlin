/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import dev.hsbrysk.caffeine.buildCoroutine
import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.client.persistentCache.*
import dev.ja.marketplace.services.JetBrainsProductCode
import io.ktor.client.*
import io.ktor.client.plugins.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Client, which caches the API responses in memory.
 *
 * If a [persistentCache] is given, the history of sales and trials and the prices of plugins are also stored on disk.
 * After a restart, the stored data is used and updated in the background, see [PersistentHistoryCache].
 * The store must only be used for the data of a single account, see [PersistentCacheStore.accountStore].
 */
class CaffeineCacheMarketplaceClient(
    apiHost: String = Marketplace.HOSTNAME,
    apiPath: String = Marketplace.API_PATH,
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2),
    httpClient: HttpClient,
    private val unstableHistoricDataDays: Int = 45,
    private val persistentCache: PersistentCacheStore? = null,
    private val persistentHistorySettings: PersistentHistorySettings = PersistentHistorySettings(overlapDays = unstableHistoricDataDays),
) : KtorMarketplaceClient(apiHost, apiPath, dispatcher, httpClient), CacheAware {
    constructor(
        apiKey: String,
        apiHost: String = Marketplace.HOSTNAME,
        apiPath: String = Marketplace.API_PATH,
        logLevel: ClientLogLevel = ClientLogLevel.None,
        enableHttpCaching: Boolean = false,
        dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2),
        persistentCacheDirectory: Path? = null,
    ) : this(
        apiHost,
        apiPath,
        dispatcher,
        KtorHttpClientFactory.createHttpClient(apiHost, apiKey, logLevel = logLevel, enableHttpCaching = enableHttpCaching),
        persistentCache = persistentCacheDirectory?.let { PersistentCacheStore(it).accountStore(apiHost, apiKey) },
    )

    private val frequentlyModifiedCacheDuration = 30.minutes
    private val rarelyModifiedCacheDuration = 1.days
    private val mostlyStaticCacheDuration = 7.days

    private val cache = Caffeine.newBuilder()
        .expireAfter(CacheExpiry())
        .maximumSize(10_000)
        .buildCoroutine()

    // background updates of the persistent cache
    private val persistentCacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistentSales = ConcurrentHashMap<PluginId, PersistentHistoryCache<PluginSale, CachedSale>>()
    private val persistentTrials = ConcurrentHashMap<PluginId, PersistentHistoryCache<PluginTrial, CachedTrial>>()
    private val persistentPrices = persistentCache?.let { PersistentPriceCache(it, persistentCacheScope) }

    // downloads are stored for a day, the monthly, daily and per-product values are requested via downloads()
    private val persistentDownloads = persistentCache?.let { PersistentResponseCache(it, persistentCacheScope, maxAge = 1.days) }

    // set after the cache was invalidated, data stored on disk is ignored afterward
    @Volatile
    private var isStoredDataIgnored = false

    override fun invalidateCache() {
        cache.synchronous().invalidateAll()

        isStoredDataIgnored = true
        persistentSales.values.forEach(PersistentHistoryCache<*, *>::invalidate)
        persistentTrials.values.forEach(PersistentHistoryCache<*, *>::invalidate)
        persistentPrices?.invalidate()
        persistentDownloads?.invalidate()
    }

    /**
     * Drops the cached data of a single plugin, e.g. its sales, trials, downloads and releases.
     * The sales and trials of the plugin are fetched again completely, the prices of all plugins are kept.
     */
    fun invalidatePlugin(plugin: PluginId) {
        // the keys of the data of a plugin have the plugin ID as second segment, e.g. "downloads.<id>..."
        val segment = plugin.toString()
        cache.synchronous().asMap().keys.removeIf { it.split('.').getOrNull(1) == segment }
        persistentSales[plugin]?.invalidate()
        persistentTrials[plugin]?.invalidate()
        persistentDownloads?.invalidate(downloadsFileName(plugin))
    }

    /**
     * Loads the stored sales and trials of the plugins in the background, which also fetches their recent days again,
     * see [PersistentHistoryCache]. This loads changes, e.g. refunds, after a start without a report being opened.
     * Plugins without stored data aren't loaded, and nothing is loaded without a persistent cache.
     */
    fun updateStoredHistory(plugins: Iterable<PluginId>) {
        val store = persistentCache ?: return
        for (plugin in plugins) {
            if (store.exists("plugin-$plugin-sales")) {
                persistentCacheScope.launch { loadInBackground(plugin, "sales") { salesInfo(plugin) } }
            }
            if (store.exists("plugin-$plugin-trials")) {
                persistentCacheScope.launch { loadInBackground(plugin, "trials") { trialsInfo(plugin) } }
            }
        }
    }

    /**
     * Waits until the background updates of the persistent cache are done, for tests.
     */
    internal suspend fun awaitPersistentCacheUpdates() {
        do {
            persistentCacheScope.coroutineContext.job.children.toList().forEach { it.join() }
            (persistentSales.values + persistentTrials.values).forEach { it.awaitBackgroundFetch() }
        } while (persistentCacheScope.coroutineContext.job.children.any { it.isActive })
    }

    private suspend fun loadInBackground(plugin: PluginId, name: String, load: suspend () -> Unit) {
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Updating the stored $name of plugin $plugin failed", e)
        }
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

    override suspend fun salesInfo(plugin: PluginId): List<PluginSale> {
        val store = persistentCache ?: return super.salesInfo(plugin)
        return persistentSales.computeIfAbsent(plugin) {
            PersistentHistoryCache(
                store,
                HistoryKind.Sales,
                "plugin-$plugin-sales",
                persistentCacheScope,
                persistentHistorySettings,
                fetcher = { range -> loadMonthlyChunks(range) { super.loadSalesInfo(plugin, it) } },
                // the licenses are derived from the sales
                onBackgroundUpdate = { cache.synchronous().invalidate(licenseInfoKey(plugin)) },
                ignoreStoredData = isStoredDataIgnored,
            )
        }.get()
    }

    override suspend fun trialsInfo(plugin: PluginId): List<PluginTrial> {
        val store = persistentCache ?: return super.trialsInfo(plugin)
        return persistentTrials.computeIfAbsent(plugin) {
            PersistentHistoryCache(
                store,
                HistoryKind.Trials,
                "plugin-$plugin-trials",
                persistentCacheScope,
                persistentHistorySettings,
                fetcher = { range -> loadMonthlyChunks(range) { super.loadTrialsInfo(plugin, it) } },
                ignoreStoredData = isStoredDataIgnored,
            )
        }.get()
    }

    override suspend fun licenseInfo(plugin: PluginId): SalesWithLicensesInfo {
        return loadCached(licenseInfoKey(plugin), frequentlyModifiedCacheDuration) {
            super.licenseInfo(plugin)
        }
    }

    override suspend fun downloadsTotal(plugin: PluginId, vararg filters: DownloadFilter): Long {
        val filterString = filters.map(DownloadFilter::toString).sorted().joinToString(",")
        val key = "downloadsTotal.$plugin.$filterString"
        return loadCached(key, frequentlyModifiedCacheDuration) {
            storedDownloads(plugin, key, Long.serializer()) { super.downloadsTotal(plugin, *filters) }
        }
    }

    override suspend fun downloads(
        plugin: PluginId,
        groupType: DownloadRequestDimension,
        countType: DownloadCountType,
        startDate: YearMonthDay?,
        vararg filters: DownloadFilter
    ): DownloadResponse {
        val filterString = filters.map(DownloadFilter::toString).sorted().joinToString(",")
        val key = "downloads.$plugin.$groupType.$countType.$startDate.$filterString"
        return loadCached(key, frequentlyModifiedCacheDuration) {
            storedDownloads(plugin, key, DownloadResponse.serializer()) { super.downloads(plugin, groupType, countType, startDate, *filters) }
        }
    }

    override suspend fun groupedDownloads(
        plugin: PluginId,
        dimension: DownloadRequestDimension,
        groupType: DownloadRequestDimension,
        countType: DownloadCountType,
        startDate: YearMonthDay?,
        vararg filters: DownloadFilter,
    ): GroupedDownloadResponse {
        val filterString = filters.map(DownloadFilter::toString).sorted().joinToString(",")
        val key = "groupedDownloads.$plugin.$dimension.$groupType.$countType.$startDate.$filterString"
        return loadCached(key, frequentlyModifiedCacheDuration) {
            storedDownloads(plugin, key, GroupedDownloadResponse.serializer()) {
                super.groupedDownloads(plugin, dimension, groupType, countType, startDate, *filters)
            }
        }
    }

    /**
     * @return The downloads of the persistent cache, which are fetched again after a day, or the loaded downloads without a persistent cache.
     */
    private suspend fun <T> storedDownloads(plugin: PluginId, key: String, serializer: KSerializer<T>, loader: suspend () -> T): T {
        return when (val store = persistentDownloads) {
            null -> loader()
            else -> store.get(downloadsFileName(plugin), key, serializer, loader)
        }
    }

    private fun downloadsFileName(plugin: PluginId): String = "plugin-$plugin-downloads"

    override suspend fun pageAnalytics(plugin: PluginId, timeFrame: PageAnalyticsTimeFrame): PluginPageAnalytics {
        return loadCached("pageAnalytics.$plugin.$timeFrame", frequentlyModifiedCacheDuration) {
            super.pageAnalytics(plugin, timeFrame)
        }
    }

    override suspend fun downloadsMonthly(
        plugin: PluginId,
        countType: DownloadCountType,
        startDate: YearMonthDay?,
        productCode: JetBrainsProductCode?
    ): List<MonthlyDownload> {
        return loadCached("downloadsMonthly.$plugin.$countType.$startDate", frequentlyModifiedCacheDuration) {
            super.downloadsMonthly(plugin, countType, startDate, productCode)
        }
    }

    override suspend fun downloadsDaily(
        plugin: PluginId,
        countType: DownloadCountType,
        startDate: YearMonthDay?,
        productCode: JetBrainsProductCode?
    ): List<DailyDownload> {
        return loadCached("downloadsDaily.$plugin.$countType.$startDate.$productCode", frequentlyModifiedCacheDuration) {
            super.downloadsDaily(plugin, countType, startDate, productCode)
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

    override suspend fun reviewComments(plugin: PluginId, pageSize: Int): List<PluginReviewComment> {
        return loadCached("reviewComments.$plugin", frequentlyModifiedCacheDuration) {
            super.reviewComments(plugin, pageSize)
        }
    }

    override suspend fun reviewCommentsSinglePage(plugin: PluginId, size: Int, page: Int): List<PluginReviewComment> {
        return loadCached("reviewComments.$plugin.$size.$page", frequentlyModifiedCacheDuration) {
            super.reviewCommentsSinglePage(plugin, size, page)
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
            when (persistentPrices) {
                null -> super.priceInfo(plugin, isoCountryCode)
                else -> persistentPrices.get(plugin, isoCountryCode) { super.priceInfo(plugin, isoCountryCode) }
            }
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

    private fun licenseInfoKey(plugin: PluginId): String = "licenseInfo.$plugin"

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

private val logger = LoggerFactory.getLogger(CaffeineCacheMarketplaceClient::class.java)
