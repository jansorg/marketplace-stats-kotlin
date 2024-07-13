/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.trackers.BaseContinuityDiscountTracker
import dev.ja.marketplace.data.trackers.ContinuityDiscountTracker
import dev.ja.marketplace.data.trackers.NoOpContinuityTracker
import dev.ja.marketplace.exchangeRate.ExchangeRates
import dev.ja.marketplace.services.Countries
import kotlinx.coroutines.Deferred

data class PluginData(
    // JetBrains Services
    val countries: Countries,

    // Exchange rates
    val exchangeRates: ExchangeRates,

    // marketplace data
    val pluginSummary: PluginInfoSummary,
    private val pluginInfoDeferred: Deferred<PluginInfo>,
    private val pluginRatingDeferred: Deferred<PluginRating>,
    private val totalDownloadsDeferred: Deferred<Long>,
    private val downloadsMonthlyDeferred: Deferred<List<MonthlyDownload>>,
    private val downloadsDailyDeferred: Deferred<List<DailyDownload>>,
    private val downloadsProductDeferred: Deferred<List<ProductDownload>>,

    // info for paid or freemium plugin/s
    val pluginPricing: PluginPricing?,
    private val salesWithLicensesDeferred: Deferred<SalesWithLicensesInfo>?,
    private val trialsDeferred: Deferred<List<PluginTrial>>?,
    private val marketplacePluginInfoDeferred: Deferred<MarketplacePluginInfo>?,
) {
    val pluginId: PluginId = pluginSummary.id

    lateinit var continuityDiscountTracker: ContinuityDiscountTracker
        private set

    suspend fun getPluginInfo(): PluginInfo {
        return pluginInfoDeferred.await()
    }

    suspend fun getPluginRating(): PluginRating {
        return pluginRatingDeferred.await()
    }

    suspend fun getTotalDownloads(): Long {
        return totalDownloadsDeferred.await()
    }

    suspend fun getDownloadsMonthly(): List<MonthlyDownload> {
        return downloadsMonthlyDeferred.await()
    }

    suspend fun getDownloadsDaily(): List<DailyDownload> {
        return downloadsDailyDeferred.await()
    }

    suspend fun getDownloadsProduct(): List<ProductDownload> {
        return downloadsProductDeferred.await()
    }

    suspend fun getSales(): List<PluginSale>? {
        return salesWithLicensesDeferred?.await()?.sales
    }

    suspend fun getLicenses(): List<LicenseInfo>? {
        return salesWithLicensesDeferred?.await()?.licenses
    }

    suspend fun getTrials(): List<PluginTrial>? {
        return trialsDeferred?.await()
    }

    suspend fun getMarketplacePluginInfo(): MarketplacePluginInfo? {
        return marketplacePluginInfoDeferred?.await()
    }

    suspend fun init() {
        when (val licenses = getLicenses()) {
            null -> {
                continuityDiscountTracker = NoOpContinuityTracker
            }

            else -> {
                continuityDiscountTracker = BaseContinuityDiscountTracker()
                licenses.forEach(continuityDiscountTracker::process)
            }
        }
    }
}