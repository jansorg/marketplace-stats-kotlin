/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.services.JetBrainsProductCode

interface MarketplaceClient : MarketplaceUrlSupport, MarketplaceClientPublic {
    suspend fun userInfo(): UserInfo

    suspend fun plugins(
        userId: UserId,
        family: List<ProductFamily>? = null,
        page: Int = 1,
        maxResults: Int? = null
    ): List<PluginInfoSummary>

    /**
     * @return all plugin sales since the inception of the marketplace until the current date
     */
    @PaidPluginAPI
    suspend fun salesInfo(plugin: PluginId): List<PluginSale>

    /**
     * @return All plugin sales returned by [salesInfo] split into the purchased licenses.
     */
    @PaidPluginAPI
    suspend fun licenseInfo(plugin: PluginId): SalesWithLicensesInfo

    /**
     * @param range Date range of sales, inclusive
     * @return plugin sales during the given range
     */
    @PaidPluginAPI
    suspend fun salesInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginSale>

    /**
     * @return all plugin trials since the inception of the marketplace until the current date
     */
    @PaidPluginAPI
    suspend fun trialsInfo(plugin: PluginId): List<PluginTrial>

    /**
     * @param plugin Plugin
     * @param range  Date range of trials to fetch, inclusive
     * @return plugin trials in the given range
     */
    @PaidPluginAPI
    suspend fun trialsInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginTrial>

    /**
     * @param plugin Download counts of this plugin will be requested
     * @param filters fixme unclear what the API allows
     * @return The total number of non-unique downloads of the given plugin.
     */
    suspend fun downloadsTotal(plugin: PluginId, vararg filters: DownloadFilter): Long

    /**
     * @param plugin Download counts of this plugin will be requested
     * @param groupType How the results are grouped
     * @param countType If all or unique downloads are counted
     * @param filters fixme unclear what the API allows
     * @return Downloads for the given filter settings.
     */
    suspend fun downloads(
        plugin: PluginId,
        groupType: DownloadRequestDimension,
        countType: DownloadCountType,
        startDate: YearMonthDay? = null,
        vararg filters: DownloadFilter,
    ): DownloadResponse

    /**
     * @param dimension [DownloadRequestDimension.Month] or [DownloadRequestDimension.Day], the dimension of the series of each group
     * @param groupType The dimension of the groups, e.g. [DownloadRequestDimension.ProductCode] or [DownloadRequestDimension.Update]
     * @param filters The API applies the filters by product ([DownloadFilter.productCode]) and by release ([DownloadFilter.update]),
     *  filters by country and by major version are ignored. Grouping by product and release in one request isn't supported by the API,
     *  but the downloads per release can be requested for each product.
     * @return Downloads grouped by two dimensions, e.g. the downloads per month of each product.
     */
    suspend fun groupedDownloads(
        plugin: PluginId,
        dimension: DownloadRequestDimension,
        groupType: DownloadRequestDimension,
        countType: DownloadCountType,
        startDate: YearMonthDay? = null,
        vararg filters: DownloadFilter,
    ): GroupedDownloadResponse

    /**
     * Visitors of the plugin's page on the Marketplace, from Google Analytics. Requires the API key of a developer of the plugin.
     * The data is sampled and may be inaccurate, see [PluginPageAnalytics.isReliable].
     */
    suspend fun pageAnalytics(plugin: PluginId, timeFrame: PageAnalyticsTimeFrame): PluginPageAnalytics

    /**
     * Helper method to retrieve downloads grouped by month.
     */
    suspend fun downloadsMonthly(
        plugin: PluginId,
        countType: DownloadCountType,
        startDate: YearMonthDay? = null,
        productCode: JetBrainsProductCode? = null
    ): List<MonthlyDownload>

    /**
     * Helper method to retrieve downloads grouped by day.
     */
    suspend fun downloadsDaily(
        plugin: PluginId,
        countType: DownloadCountType,
        startDate: YearMonthDay? = null,
        productCode: JetBrainsProductCode? = null
    ): List<DailyDownload>

    /**
     * Helper method to retrieve downloads grouped by product.
     */
    suspend fun downloadsByProduct(plugin: PluginId, countType: DownloadCountType): List<ProductDownload>

    /**
     * @return The volume discounts defined for this plugin.
     */
    @PaidPluginAPI
    suspend fun volumeDiscounts(plugin: PluginId): VolumeDiscountResponse
}