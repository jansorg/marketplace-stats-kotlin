/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

interface MarketplaceClient : MarketplaceUrlSupport {
    suspend fun userInfo(): UserInfo

    suspend fun plugins(userId: UserId): List<PluginInfoSummary>

    suspend fun pluginInfo(id: PluginId): PluginInfo

    suspend fun pluginRating(id: PluginId): PluginRating

    /**
     * @return all plugin sales since the inception of the marketplace until the current date
     */
    suspend fun salesInfo(plugin: PluginId): List<PluginSale>

    /**
     * @param range Date range of sales, inclusive
     * @return plugin sales during the given range
     */
    suspend fun salesInfo(plugin: PluginId, range: YearMonthDayRange): List<PluginSale>

    /**
     * @return all plugin trials since the inception of the marketplace until the current date
     */
    suspend fun trialsInfo(plugin: PluginId): List<PluginTrial>

    /**
     * @param plugin Plugin
     * @param range  Date range of trials to fetch, inclusive
     * @return plugin trials in the given range
     */
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
        groupType: DownloadDimensionRequest,
        countType: DownloadCountType,
        vararg filters: DownloadFilter,
    ): DownloadResponse

    /**
     * Helper method to retrieve downloads grouped by month.
     */
    suspend fun downloadsMonthly(plugin: PluginId, countType: DownloadCountType): List<MonthlyDownload>

    /**
     * Helper method to retrieve downloads grouped by day.
     */
    suspend fun downloadsDaily(plugin: PluginId, countType: DownloadCountType): List<DailyDownload>

    /**
     * Helper method to retrieve downloads grouped by product.
     */
    suspend fun downloadsByProduct(plugin: PluginId, countType: DownloadCountType): List<ProductDownload>

    /**
     * @return The list of JetBrains products, which are compatible with the given plugin.
     */
    suspend fun compatibleProducts(plugin: PluginId): List<JetBrainsProductId>

    /**
     * @return The volume discounts defined for this plugin.
     */
    suspend fun volumeDiscounts(plugin: PluginId): List<VolumeDiscountResponse>

    /**
     * @return The public plugin information of the given plugin.
     */
    suspend fun marketplacePluginInfo(plugin: PluginId, fullInfo: Boolean = false): MarketplacePluginInfo
}