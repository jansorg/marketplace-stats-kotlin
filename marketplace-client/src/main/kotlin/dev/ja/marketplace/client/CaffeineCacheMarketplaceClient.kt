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
import io.ktor.client.plugins.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import dev.ja.marketplace.client.CachingStrategy.Category
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Client, which caches the API responses in memory.
 *
 * The durations of the cached data are defined by the [cachingStrategy].
 * If a [persistentCache] is given, the history of sales and trials and the prices of plugins are also stored on disk.
 * After a restart, the stored data is used and updated in the background, see [PersistentHistoryCache].
 * The store must only be used for the data of a single account, see [PersistentCacheStore.accountStore].
 */
class CaffeineCacheMarketplaceClient(
    apiKey: String? = null,
    httpClientBuilder: HttpClientBuilder = HttpClientFactory.builder(),
    apiHost: String = Marketplace.HOSTNAME,
    apiPath: String = Marketplace.API_PATH,
    processingDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2),
    private val persistentCache: PersistentCacheStore? = null,
    private val cachingStrategy: CachingStrategy = CachingStrategy(),
) : KtorMarketplaceClient(apiKey, httpClientBuilder, apiHost, apiPath, processingDispatcher), CacheAware {
    private val persistentHistorySettings = cachingStrategy.historySettings

    private val cache = Caffeine.newBuilder()
        .expireAfter(CacheExpiry())
        .maximumSize(10_000)
        .buildCoroutine()

    // background updates of the persistent cache
    // background work, its requests have a lower limit than the requests of pages
    private val persistentCacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + BackgroundRequests)
    private val persistentSales = ConcurrentHashMap<PluginId, PersistentHistoryCache<PluginSale, CachedSale>>()
    private val persistentTrials = ConcurrentHashMap<PluginId, PersistentHistoryCache<PluginTrial, CachedTrial>>()
    private val persistentPrices = persistentCache?.let {
        PersistentPriceCache(it, persistentCacheScope, maxAge = cachingStrategy.maxAge(Category.Static))
    }

    // the downloads of yesterday are published late, responses fetched before are cached briefly
    private val downloadsCompleteness = DownloadsCompleteness()

    // the monthly, daily and per-product downloads are requested via downloads()
    private val persistentDownloads = persistentCache?.let {
        PersistentResponseCache(it, persistentCacheScope, maxAge = cachingStrategy.maxAge(Category.Daily))
    }

    // the values, which refreshPlugin is fetching: a request of a missing value waits for it instead of fetching it again
    private val refreshing = ConcurrentHashMap<String, Deferred<Stored<Any?>>>()

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
     * Writes the pending changes of the prices and downloads, cancels the background updates of the persistent cache
     * and closes the HTTP clients. The files of the persistent cache are written atomically, a cancelled update doesn't
     * leave a partial file.
     */
    override fun close() {
        // the changes, which are written with a delay, are written before the writes are cancelled
        persistentPrices?.flush()
        persistentDownloads?.flush()
        persistentCacheScope.cancel()
        super.close()
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
     * Fetches the cached data of a plugin again, which would be outdated before the next automatic refresh,
     * see [CachingStrategy.isRefreshDue]. Requests use the cached data, while it's fetched.
     * Failures are logged and the cached data is kept. Nothing is fetched without the automatic refresh.
     */
    suspend fun refreshPlugin(plugin: PluginId): Unit = withContext(BackgroundRequests) {
        if (cachingStrategy.autoRefreshInterval == null) {
            return@withContext
        }

        val now = System.currentTimeMillis()
        val segment = plugin.toString()
        // taken before the sales are updated, which drops the cached licenses of the plugin
        val entries = cache.synchronous().asMap().entries
            .filter { (key, value) ->
                key.split('.').getOrNull(1) == segment && cachingStrategy.isRefreshDue(value.category, (now - value.createdMillis).milliseconds)
            }
            .map { it.key to it.value }
            .sortedBy { (key, _) -> refreshOrder(key) }

        persistentSales[plugin]?.refresh()
        persistentTrials[plugin]?.refresh()
        for ((key, value) in entries) {
            val inFlight = CompletableDeferred<Stored<Any?>>()
            refreshing[key] = inFlight
            try {
                val refreshed = value.refresh()
                inFlight.complete(refreshed)
                val result = refreshed.value ?: continue
                // the category of the refreshed data, e.g. downloads, which are complete now
                val category = value.categoryOf(refreshed)
                cache.synchronous().put(key, CacheValue(category, cachingStrategy.expiry(category), result, null, value.refresh, value.categoryOf))
            } catch (e: CancellationException) {
                inFlight.cancel(e)
                throw e
            } catch (e: Exception) {
                inFlight.completeExceptionally(e)
                logger.warn("Unable to refresh the cached data $key of plugin $plugin, the cached data is kept: $e")
            } finally {
                refreshing.remove(key, inFlight)
            }
        }
    }

    /**
     * Data is refreshed before the data derived from it, e.g. the sales before the licenses and the downloads
     * before the monthly downloads.
     */
    private fun refreshOrder(key: String): Int {
        return when (key.substringBefore('.')) {
            "loadSalesInfo", "loadTrialsInfo", "downloads", "downloadsTotal", "groupedDownloads" -> 0
            "licenseInfo" -> 2
            else -> 1
        }
    }

    /**
     * Waits until the background updates of the persistent cache are done, for tests.
     */
    internal suspend fun awaitPersistentCacheUpdates() {
        do {
            persistentCacheScope.coroutineContext.job.children.toList().joinAll()
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
        return loadCached("userInfo", Category.Daily) {
            super.userInfo()
        }
    }

    override suspend fun vendors(): List<PluginVendor> {
        return loadCached("vendors", Category.Daily) {
            super.vendors()
        }
    }

    override suspend fun checkLicense(productCode: String, email: String): LicenseCheckResult {
        return loadCached("checkLicense.$productCode.${email.lowercase()}", Category.Recent) {
            super.checkLicense(productCode, email)
        }
    }

    override suspend fun plugins(userId: UserId, family: List<ProductFamily>?, page: Int, maxResults: Int?): List<PluginInfoSummary> {
        val families = family?.joinToString(",", transform = ProductFamily::id) ?: ""
        return loadCached("plugins.$userId.$families.$page.${maxResults ?: -1}", Category.Daily) {
            super.plugins(userId, family, page, maxResults)
        }
    }

    override suspend fun pluginInfo(plugin: PluginId): PluginInfo {
        return loadCached("pluginInfo.$plugin", Category.Daily) {
            super.pluginInfo(plugin)
        }
    }

    override suspend fun pluginRatingByCategory(id: PluginId): Map<PluginRatingCategory, PluginCategoryRating> {
        return loadCached("pluginRatingByCategory.$id", Category.Daily) {
            super.pluginRatingByCategory(id)
        }
    }

    override suspend fun verificationResults(pluginReleaseId: PluginReleaseId, type: PluginVerificationType): List<PluginVerificationResult> {
        return loadCached("verificationResults.$pluginReleaseId.$type", Category.Daily) {
            super.verificationResults(pluginReleaseId, type)
        }
    }

    override suspend fun pluginRating(id: PluginId): PluginRating {
        return loadCached("pluginRating.$id", Category.Daily) {
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
        return loadCached(licenseInfoKey(plugin), Category.Recent) {
            super.licenseInfo(plugin)
        }
    }

    override suspend fun downloadsTotal(plugin: PluginId, vararg filters: DownloadFilter): Long {
        val filterString = filters.map(DownloadFilter::toString).sorted().joinToString(",")
        val key = "downloadsTotal.$plugin.$filterString"
        val category: (Stored<Long>) -> Category = { downloadsCompleteness.category(plugin, it.fetchedMillis) }
        val loader: suspend (Boolean) -> Stored<Long> = { forceFetch ->
            storedDownloads(plugin, key, Long.serializer(), forceFetch, category) { super.downloadsTotal(plugin, *filters) }
        }
        return loadCachedStored(key, Category.Daily, refresh = { loader(true) }, categoryOf = category) { loader(false) }
    }

    override suspend fun downloads(
        plugin: PluginId,
        groupType: DownloadRequestDimension,
        countType: DownloadCountType,
        startDate: YearMonthDay?,
        vararg filters: DownloadFilter,
        endDate: YearMonthDay?,
    ): DownloadResponse {
        val filterString = filters.map(DownloadFilter::toString).sorted().joinToString(",")
        val key = "downloads.$plugin.$groupType.$countType.$startDate.$filterString" + (endDate?.let { ".$it" } ?: "")
        val category: (Stored<DownloadResponse>) -> Category = { stored ->
            if (groupType == DownloadRequestDimension.Day) {
                downloadsCompleteness.recordDays(plugin, DownloadsCompleteness.days(stored.value.data.serie), stored.fetchedMillis)
            }
            downloadsCompleteness.category(plugin, stored.fetchedMillis, endDate)
        }
        val loader: suspend (Boolean) -> Stored<DownloadResponse> = { forceFetch ->
            storedDownloads(plugin, key, DownloadResponse.serializer(), forceFetch, category) {
                super.downloads(plugin, groupType, countType, startDate, *filters, endDate = endDate)
            }
        }
        return loadCachedStored(key, Category.Daily, refresh = { loader(true) }, categoryOf = category) { loader(false) }
    }

    override suspend fun groupedDownloads(
        plugin: PluginId,
        dimension: DownloadRequestDimension,
        groupType: DownloadRequestDimension,
        countType: DownloadCountType,
        startDate: YearMonthDay?,
        vararg filters: DownloadFilter,
        endDate: YearMonthDay?,
    ): GroupedDownloadResponse {
        val filterString = filters.map(DownloadFilter::toString).sorted().joinToString(",")
        val key = "groupedDownloads.$plugin.$dimension.$groupType.$countType.$startDate.$filterString" + (endDate?.let { ".$it" } ?: "")
        val category: (Stored<GroupedDownloadResponse>) -> Category = { stored ->
            if (dimension == DownloadRequestDimension.Day) {
                downloadsCompleteness.recordDays(plugin, DownloadsCompleteness.days(stored.value.groups.flatMap { it.serie }), stored.fetchedMillis)
            }
            downloadsCompleteness.category(plugin, stored.fetchedMillis, endDate)
        }
        val loader: suspend (Boolean) -> Stored<GroupedDownloadResponse> = { forceFetch ->
            storedDownloads(plugin, key, GroupedDownloadResponse.serializer(), forceFetch, category) {
                super.groupedDownloads(plugin, dimension, groupType, countType, startDate, *filters, endDate = endDate)
            }
        }
        return loadCachedStored(key, Category.Daily, refresh = { loader(true) }, categoryOf = category) { loader(false) }
    }

    /**
     * @param forceFetch If the downloads are fetched also if they're stored, e.g. by the automatic refresh.
     * @param category The category of the stored downloads: downloads fetched before the downloads of yesterday were
     *  published are fetched again after the age of [Category.Recent], see [DownloadsCompleteness].
     * @return The downloads of the persistent cache, which are fetched again after a day, or the loaded downloads without a persistent cache.
     */
    private suspend fun <T> storedDownloads(
        plugin: PluginId,
        key: String,
        serializer: KSerializer<T>,
        forceFetch: Boolean,
        category: (Stored<T>) -> Category,
        loader: suspend () -> T,
    ): Stored<T> {
        val store = persistentDownloads ?: return Stored(loader(), System.currentTimeMillis())
        val stored = store.get(downloadsFileName(plugin), key, serializer, forceFetch, loader)
        val age = (System.currentTimeMillis() - stored.fetchedMillis).milliseconds
        return when {
            !forceFetch && category(stored) == Category.Recent && age >= cachingStrategy.maxAge(Category.Recent) ->
                store.get(downloadsFileName(plugin), key, serializer, true, loader)

            else -> stored
        }
    }

    private fun downloadsFileName(plugin: PluginId): String = "plugin-$plugin-downloads"

    override suspend fun pageAnalytics(plugin: PluginId, timeFrame: PageAnalyticsTimeFrame): PluginPageAnalytics {
        return loadCached("pageAnalytics.$plugin.$timeFrame", Category.Daily) {
            super.pageAnalytics(plugin, timeFrame)
        }
    }

    override suspend fun downloadsMonthly(
        plugin: PluginId,
        countType: DownloadCountType,
        startDate: YearMonthDay?,
        productCode: JetBrainsProductCode?
    ): List<MonthlyDownload> {
        // derived from the cached downloads(), whose category depends on the published downloads
        return super.downloadsMonthly(plugin, countType, startDate, productCode)
    }

    override suspend fun downloadsDaily(
        plugin: PluginId,
        countType: DownloadCountType,
        startDate: YearMonthDay?,
        productCode: JetBrainsProductCode?
    ): List<DailyDownload> {
        // derived from the cached downloads(), whose category depends on the published downloads
        return super.downloadsDaily(plugin, countType, startDate, productCode)
    }

    override suspend fun downloadsByProduct(plugin: PluginId, countType: DownloadCountType): List<ProductDownload> {
        // derived from the cached downloads()
        return super.downloadsByProduct(plugin, countType)
    }

    override suspend fun compatibleProducts(plugin: PluginId): List<JetBrainsProductId> {
        return loadCached("compatibleProducts.$plugin", Category.Daily) {
            super.compatibleProducts(plugin)
        }
    }

    override suspend fun volumeDiscounts(plugin: PluginId): VolumeDiscountResponse {
        return loadCached("volumeDiscount.$plugin", Category.Daily) {
            super.volumeDiscounts(plugin)
        }
    }

    override suspend fun releaseDates(plugin: PluginId): List<PluginReleaseDate> {
        return loadCached("releaseDates.$plugin", Category.Daily) {
            super.releaseDates(plugin)
        }
    }

    override suspend fun offers(plugin: PluginId): List<PluginOffer> {
        return loadCached("offers.$plugin", Category.Recent) {
            super.offers(plugin)
        }
    }

    override suspend fun offer(plugin: PluginId, offer: PluginOfferId): PluginOffer {
        return loadCached("offer.$plugin.$offer", Category.Recent) {
            super.offer(plugin, offer)
        }
    }

    override suspend fun offerCoupons(plugin: PluginId, offer: PluginOfferId): List<PluginOfferCoupon> {
        return loadCached("offerCoupons.$plugin.$offer", Category.Recent) {
            super.offerCoupons(plugin, offer)
        }
    }

    override suspend fun monetization(plugin: PluginId): PluginMonetization {
        return loadCached("monetization.$plugin", Category.Daily) {
            super.monetization(plugin)
        }
    }

    override suspend fun salesAnalyticsReport(plugin: PluginId): List<SalesAnalyticsReportRow> {
        return loadCached("salesAnalyticsReport.$plugin", Category.Recent) {
            super.salesAnalyticsReport(plugin)
        }
    }

    override suspend fun fullSalesReport(plugin: PluginId): List<FullSalesReportRow> {
        return loadCached("fullSalesReport.$plugin", Category.Recent) {
            super.fullSalesReport(plugin)
        }
    }

    override suspend fun marketplacePluginInfo(plugin: PluginId, fullInfo: Boolean): MarketplacePluginInfo {
        return loadCached("marketplacePluginInfo.$plugin.$fullInfo", Category.Daily) {
            super.marketplacePluginInfo(plugin, fullInfo)
        }
    }

    override suspend fun marketplacePluginsSearchSinglePage(request: MarketplacePluginSearchRequest): MarketplacePluginSearchResponse {
        return loadCached("marketplaceSearchPluginsPage.$request", Category.Recent) {
            super.marketplacePluginsSearchSinglePage(request)
        }
    }

    override suspend fun marketplacePluginsSearch(
        request: MarketplacePluginSearchRequest,
        pageSize: Int
    ): List<MarketplacePluginSearchResultItem> {
        return loadCached("marketplaceSearchPlugins.$request.$pageSize", Category.Recent) {
            super.marketplacePluginsSearch(request, pageSize)
        }
    }

    override suspend fun marketplaceTags(): List<PluginTag> {
        return loadCached("marketplaceTags", Category.Daily) {
            super.marketplaceTags()
        }
    }

    override suspend fun reviewComments(plugin: PluginId, pageSize: Int): List<PluginReviewComment> {
        return loadCached("reviewComments.$plugin", Category.Recent) {
            super.reviewComments(plugin, pageSize)
        }
    }

    override suspend fun reviewCommentsSinglePage(plugin: PluginId, size: Int, page: Int): List<PluginReviewComment> {
        return loadCached("reviewComments.$plugin.$size.$page", Category.Recent) {
            super.reviewCommentsSinglePage(plugin, size, page)
        }
    }

    override suspend fun reviewReplies(comment: PluginReviewId): List<PluginReviewComment> {
        return loadCached("reviewReplies.$comment", Category.Recent) {
            super.reviewReplies(comment)
        }
    }

    override suspend fun channels(plugin: PluginId): List<PluginChannel> {
        return loadCached("channels.$plugin", Category.Daily) {
            super.channels(plugin)
        }
    }

    override suspend fun releasesSinglePage(plugin: PluginId, channel: PluginChannel, size: Int, page: Int): List<PluginReleaseInfo> {
        return loadCached("releases.$plugin.$channel.$size.$page", Category.Daily) {
            super.releasesSinglePage(plugin, channel, size, page)
        }
    }

    override suspend fun pluginReleaseDependencies(pluginReleaseId: PluginReleaseId): List<PluginDependency> {
        // the dependencies of a release don't change
        return loadCached("pluginReleaseDependencies.$pluginReleaseId", Category.Static) {
            super.pluginReleaseDependencies(pluginReleaseId)
        }
    }

    override suspend fun unsupportedReleaseProducts(pluginReleaseId: PluginReleaseId): List<PluginUnsupportedProduct> {
        return loadCached("unsupportedReleaseProducts.$pluginReleaseId", Category.Daily) {
            super.unsupportedReleaseProducts(pluginReleaseId)
        }
    }

    override suspend fun pluginDevelopers(plugin: PluginId): List<JetBrainsAccountInfo> {
        return loadCached("pluginDevelopers.$plugin", Category.Daily) {
            super.pluginDevelopers(plugin)
        }
    }

    override suspend fun pluginFeatures(plugin: PluginId): List<PluginFeature> {
        return loadCached("pluginFeatures.$plugin", Category.Daily) {
            super.pluginFeatures(plugin)
        }
    }

    override suspend fun marketplacePrograms(plugin: PluginId): List<MarketplaceProgram> {
        return loadCached("marketplacePrograms.$plugin", Category.Daily) {
            super.marketplacePrograms(plugin)
        }
    }

    override suspend fun userVendors(userId: UserId): List<PluginVendor> {
        return loadCached("userVendors.$userId", Category.Daily) {
            super.userVendors(userId)
        }
    }

    override suspend fun priceInfo(plugin: PluginId, isoCountryCode: String): PluginPriceInfo? {
        // a country without prices is cached like prices, the cache can't store null
        val loader: suspend (Boolean) -> Stored<CountryPrices> = { forceFetch ->
            val stored = when (persistentPrices) {
                null -> Stored(super.priceInfo(plugin, isoCountryCode), System.currentTimeMillis())
                else -> persistentPrices.get(plugin, isoCountryCode, forceFetch) { super.priceInfo(plugin, isoCountryCode) }
            }
            Stored(CountryPrices(stored.value), stored.fetchedMillis)
        }
        return loadCachedStored("priceInfo.$plugin.$isoCountryCode", Category.Static, refresh = { loader(true) }) { loader(false) }.prices
    }

    private class CountryPrices(val prices: PluginPriceInfo?)

    override suspend fun loadSalesInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginSale> {
        return loadCached("loadSalesInfo.$plugin.$range", cachingStrategy.historyCategory(range)) {
            super.loadSalesInfo(plugin, range)
        }
    }

    override suspend fun loadTrialsInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginTrial> {
        return loadCached("loadTrialsInfo.$plugin.$range", cachingStrategy.historyCategory(range)) {
            super.loadTrialsInfo(plugin, range)
        }
    }

    private fun licenseInfoKey(plugin: PluginId): String = "licenseInfo.$plugin"

    /**
     * @param refresh Fetches the data again for the automatic refresh, `null` to use the [loader].
     *  It must not use stored data, which would be outdated.
     */
    private suspend fun <T> loadCached(key: String, category: Category, refresh: (suspend () -> T)? = null, loader: suspend () -> T): T {
        val refreshValue = refresh ?: loader
        return loadCachedStored(
            key,
            category,
            refresh = { Stored(refreshValue(), System.currentTimeMillis()) },
            loader = { Stored(loader(), System.currentTimeMillis()) },
        )
    }

    /**
     * Like [loadCached], for data of a persistent cache: data read from disk is only kept in memory for the rest of its
     * lifetime, its age is the time since it was fetched, not since it was read.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> loadCachedStored(
        key: String,
        category: Category,
        refresh: suspend () -> Stored<T>,
        /** The category of the loaded data, e.g. by its content, by default [category]. */
        categoryOf: (Stored<T>) -> Category = { category },
        loader: suspend () -> Stored<T>,
    ): T {
        val expiry = cachingStrategy.expiry(category)
        val refreshValue = refresh as suspend () -> Stored<Any?>
        val categoryOfValue = categoryOf as (Stored<Any?>) -> Category
        val cachedValue = cache.get(key) {
            // e.g. a value, which expired while the automatic refresh fetches it
            awaitRefresh(key)?.let { refreshed ->
                val refreshedCategory = categoryOfValue(refreshed)
                return@get CacheValue(refreshedCategory, cachingStrategy.expiry(refreshedCategory), refreshed.value, null, refreshValue, categoryOfValue)
            }

            try {
                val stored = loader()
                val storedCategory = categoryOf(stored)
                val age = (System.currentTimeMillis() - stored.fetchedMillis).coerceAtLeast(0).milliseconds
                // a short minimum, so that outdated stored data isn't read again by every request
                CacheValue(storedCategory, (cachingStrategy.expiry(storedCategory) - age).coerceAtLeast(1.minutes), stored.value, null, refreshValue, categoryOfValue, stored.fetchedMillis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                // Only missing data is cached, briefly. Other errors, e.g. too many requests or a rejected API key,
                // aren't cached, the next request tries again.
                if (e.response.status != HttpStatusCode.NotFound) {
                    throw e
                }
                CacheValue(category, minOf(expiry, NOT_FOUND_EXPIRY), null, e, refreshValue, categoryOfValue)
            } catch (e: Exception) {
                // Server errors and failed connections, after the retries of the HTTP client, are cached briefly,
                // so that the following requests, e.g. of the other tables of a page, don't try again at once.
                if (!e.isTemporaryFailure()) {
                    throw e
                }
                CacheValue(category, minOf(expiry, FAILURE_EXPIRY), null, e, refreshValue, categoryOfValue)
            }
        } ?: throw IllegalStateException("Failed to compute cache value for $key")

        if (cachedValue.result != null) {
            return cachedValue.result as T
        }

        throw cachedValue.exception!!
    }

    /**
     * @return The value, which [refreshPlugin] is fetching, `null` if it isn't fetching it or if it failed.
     */
    private suspend fun awaitRefresh(key: String): Stored<Any?>? {
        val inFlight = refreshing[key] ?: return null
        return try {
            inFlight.await().takeIf { it.value != null }
        } catch (e: CancellationException) {
            // a cancelled refresh doesn't cancel the request
            if (!inFlight.isCancelled) throw e
            null
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * @param expireAfterWrite How long requests use the value, see [CachingStrategy.expiry].
 * @param refresh Fetches the value again, see [CaffeineCacheMarketplaceClient.refreshPlugin].
 * @param categoryOf The category of a fetched value, e.g. by its content.
 */
private class CacheValue(
    val category: Category,
    val expireAfterWrite: Duration,
    val result: Any?,
    val exception: Exception?,
    val refresh: suspend () -> Stored<Any?>,
    val categoryOf: (Stored<Any?>) -> Category,
    val createdMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(result != null || exception != null)
    }
}

private class CacheExpiry : Expiry<String, CacheValue> {
    override fun expireAfterCreate(key: String, value: CacheValue, currentTime: Long): Long {
        return value.expireAfterWrite.inWholeNanoseconds
    }

    // a refreshed value replaces the previous value, it expires after its own duration
    override fun expireAfterUpdate(key: String, value: CacheValue, currentTime: Long, currentDuration: Long): Long {
        return value.expireAfterWrite.inWholeNanoseconds
    }

    override fun expireAfterRead(key: String, value: CacheValue, currentTime: Long, currentDuration: Long): Long {
        return currentDuration
    }
}

private val logger = LoggerFactory.getLogger(CaffeineCacheMarketplaceClient::class.java)

/** How long requests use the response, that data doesn't exist, e.g. of a removed plugin. */
private val NOT_FOUND_EXPIRY: Duration = 5.minutes

/** How long requests use a temporary failure, see [isTemporaryFailure]. */
private val FAILURE_EXPIRY: Duration = 1.minutes

/**
 * A server error or a failed connection, e.g. a timeout or an unknown host, but not a rejected request.
 * The Java engine throws [java.nio.channels.UnresolvedAddressException] for an unknown host, it isn't an IOException.
 */
private fun Exception.isTemporaryFailure(): Boolean {
    return this is ServerResponseException || this is java.io.IOException || this is java.nio.channels.UnresolvedAddressException
}
