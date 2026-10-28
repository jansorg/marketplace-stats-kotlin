/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.services.JetBrainsProductCode

/**
 * Client of the API of the Marketplace. [close] closes its HTTP clients.
 */
interface MarketplaceClient : MarketplaceUrlSupport, MarketplaceClientPublic, AutoCloseable {
    suspend fun userInfo(): UserInfo

    suspend fun plugins(
        userId: UserId,
        family: List<ProductFamily>? = null,
        page: Int = 1,
        maxResults: Int? = null
    ): List<PluginInfoSummary>

    /**
     * @return The plugins of the user of all pages of [plugins].
     */
    suspend fun allPlugins(userId: UserId, family: List<ProductFamily>? = null): List<PluginInfoSummary>

    /**
     * @return The organizations of the account, i.e. the vendors of its plugins, with their contact details.
     */
    suspend fun vendors(): List<PluginVendor>

    /**
     * The Check License API of the Marketplace, see https://plugins.jetbrains.com/docs/marketplace/check-license.html.
     * Requires the API key of a developer of the plugin.
     *
     * @param productCode The product code of the paid plugin, see [PluginPurchaseInfo.productCode].
     * @param email The email of the account of the customer at JetBrains.
     * @return The licenses and trials of the customer for the plugin, also of organizations, which assigned a license.
     */
    @PaidPluginAPI
    suspend fun checkLicense(productCode: String, email: String): LicenseCheckResult

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
        endDate: YearMonthDay? = null,
    ): DownloadResponse

    /**
     * @param dimension [DownloadRequestDimension.Month] or [DownloadRequestDimension.Day], the dimension of the series of each group
     * @param groupType The dimension of the groups, e.g. [DownloadRequestDimension.ProductCode] or [DownloadRequestDimension.Update]
     * @param filters The API applies the filters by product ([DownloadFilter.productCode]) and by release ([DownloadFilter.update]),
     *  filters by country and by major version are ignored. Grouping by product and release in one request isn't supported by the API,
     *  but the downloads per release can be requested for each product. The API doesn't support grouping by country.
     * @param endDate The last day of the downloads, today if it's `null`.
     * @return Downloads grouped by two dimensions, e.g. the downloads per month of each product.
     */
    suspend fun groupedDownloads(
        plugin: PluginId,
        dimension: DownloadRequestDimension,
        groupType: DownloadRequestDimension,
        countType: DownloadCountType,
        startDate: YearMonthDay? = null,
        vararg filters: DownloadFilter,
        endDate: YearMonthDay? = null,
    ): GroupedDownloadResponse

    /**
     * Visitors of the plugin's page on the Marketplace, from Google Analytics. Requires the API key of a developer of the plugin.
     * The data is sampled and may be inaccurate, see [PluginPageAnalytics.isReliable].
     */
    suspend fun pageAnalytics(plugin: PluginId, timeFrame: PageAnalyticsTimeFrame): PluginPageAnalytics

    /**
     * The results of the verifiers of a release, e.g. of the Plugin Verifier. Requires the API key of a developer of the
     * plugin, also for free plugins, the API answers other requests with 403.
     *
     * @return The results of the verification of the release, one for each verified IDE.
     */
    suspend fun verificationResults(
        pluginReleaseId: PluginReleaseId,
        type: PluginVerificationType = PluginVerificationType.IntelliJCompatibility,
    ): List<PluginVerificationResult>

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

    /**
     * @return The release dates of the versions of the plugin, which the Marketplace uses for perpetual fallback licenses.
     */
    @PaidPluginAPI
    suspend fun releaseDates(plugin: PluginId): List<PluginReleaseDate>

    /**
     * @return The special offers of the plugin, which are redeemed with coupons.
     */
    @PaidPluginAPI
    suspend fun offers(plugin: PluginId): List<PluginOffer>

    @PaidPluginAPI
    suspend fun offer(plugin: PluginId, offer: PluginOfferId): PluginOffer

    /**
     * @return The coupons of a special offer of the plugin.
     */
    @PaidPluginAPI
    suspend fun offerCoupons(plugin: PluginId, offer: PluginOfferId): List<PluginOfferCoupon>

    /**
     * @return The monetization settings of the plugin, e.g. its product code and pricing model.
     */
    @PaidPluginAPI
    suspend fun monetization(plugin: PluginId): PluginMonetization

    /**
     * @return The sales analytics report: the sales of each day, grouped by customer type, license type and billing period.
     * The Marketplace creates the report as a CSV file, which is downloaded from a temporary URL.
     */
    @PaidPluginAPI
    suspend fun salesAnalyticsReport(plugin: PluginId): List<SalesAnalyticsReportRow>

    /**
     * @return The full sales report: each license of each sale, including free licenses, e.g. for trials.
     * The Marketplace creates the report as a CSV file, which is downloaded from a temporary URL.
     */
    @PaidPluginAPI
    suspend fun fullSalesReport(plugin: PluginId): List<FullSalesReportRow>
}
