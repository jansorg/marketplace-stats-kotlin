/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.data.subscriptions.CustomerRecurringRevenue
import dev.ja.marketplace.exchangeRate.ExchangeRates
import dev.ja.marketplace.services.Countries
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

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

    // info for paid or freemium plugin/s
    /** The prices, `null` for free plugins. */
    val pluginPricing: PluginPricing?,
    private val salesWithLicensesDeferred: Deferred<SalesWithLicensesInfo>?,
    private val trialsDeferred: Deferred<List<PluginTrial>>?,
    /** `null` for free plugins, the value is `null` if the info couldn't be loaded. */
    private val marketplacePluginInfoDeferred: Deferred<MarketplacePluginInfo?>? = null,
) {
    val pluginId: PluginId = pluginSummary.id

    private val customerRecurringRevenueLock = Mutex()
    private var customerRecurringRevenue: CustomerRecurringRevenue? = null

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

    suspend fun getSales(): List<PluginSale>? {
        return salesWithLicensesDeferred?.await()?.sales
    }

    suspend fun getLicenses(): List<LicenseInfo>? {
        return salesWithLicensesDeferred?.await()?.licenses
    }

    suspend fun getTrials(): List<PluginTrial>? {
        return trialsDeferred?.await()
    }

    /**
     * @return The license periods, which customers can buy, or `null` if they're unknown.
     * @see MarketplacePluginInfo.offeredLicensePeriods
     */
    suspend fun getOfferedLicensePeriods(): Set<LicensePeriod>? {
        return marketplacePluginInfoDeferred?.await()?.offeredLicensePeriods
    }

    /** @return The info of a paid or freemium plugin, e.g. its continuity discount, `null` if it's unknown. */
    suspend fun getMarketplacePluginInfo(): MarketplacePluginInfo? {
        return marketplacePluginInfoDeferred?.await()
    }

    /**
     * The recurring revenue of each customer, calculated from all licenses.
     * It's calculated once and shared by the tables of a page, because the calculation is expensive.
     * The data of each request is new, but the client returns the same licenses until it loads them again,
     * the result is reused by the next requests of the same day.
     */
    suspend fun getCustomerRecurringRevenue(): CustomerRecurringRevenue {
        return customerRecurringRevenueLock.withLock {
            customerRecurringRevenue ?: run {
                val licenses = getLicenses() ?: emptyList()
                val today = YearMonthDay.now()
                val cached = recurringRevenueCache[pluginId]
                val revenue = when {
                    cached != null && cached.licenses === licenses && cached.today == today && cached.exchangeRates === exchangeRates -> cached.revenue
                    else -> CustomerRecurringRevenue(licenses, exchangeRates, today).also {
                        recurringRevenueCache[pluginId] = CachedRecurringRevenue(licenses, exchangeRates, today, it)
                    }
                }
                revenue.also { customerRecurringRevenue = it }
            }
        }
    }

    /**
     * @return The version of the loaded data, which changes, when the client loads new data, e.g. after the cached data
     *  expired, after a refresh or a reload, on the next day or after an update of the exchange rates. The prices of
     *  [pluginPricing] aren't part of it, they're loaded when they're used.
     */
    suspend fun dataVersion(): PluginDataVersion {
        return PluginDataVersion(
            listOf(
                YearMonthDay.now(),
                exchangeRates.targetCurrency,
                exchangeRates.dataVersion,
                countries,
                pluginSummary,
                getPluginInfo(),
                getPluginRating(),
                getTotalDownloads(),
                getDownloadsMonthly(),
                getDownloadsDaily(),
                salesWithLicensesDeferred?.await(),
                getTrials(),
                marketplacePluginInfoDeferred?.await(),
            )
        )
    }

    /**
     * @return The first day with a sale, a license, a trial or a download, or `null` if there's no such day.
     *  Like the charts, see [dev.ja.marketplace.data.chart.ChartMetricValues].
     */
    suspend fun getFirstActivityDate(): YearMonthDay? {
        return listOfNotNull(
            getSales()?.minOfOrNull { it.date },
            getLicenses()?.mapNotNull { it.validity?.start }?.minOrNull(),
            getTrials()?.minOfOrNull { it.date },
            getDownloadsDaily().filter { it.downloads > 0 }.minOfOrNull { it.day },
        ).minOrNull()
    }

    /**
     * @return A copy of this data, which only contains the trials matching [predicate].
     */
    suspend fun filterTrials(predicate: (PluginTrial) -> Boolean): PluginData {
        val trials = trialsDeferred?.await() ?: return this
        return copy(trialsDeferred = CompletableDeferred(trials.filter(predicate)))
    }

    private class CachedRecurringRevenue(
        val licenses: List<LicenseInfo>,
        val exchangeRates: ExchangeRates,
        val today: YearMonthDay,
        val revenue: CustomerRecurringRevenue,
    )

    private companion object {
        /** The recurring revenue of the latest licenses of each plugin, see [getCustomerRecurringRevenue]. */
        val recurringRevenueCache = ConcurrentHashMap<PluginId, CachedRecurringRevenue>()
    }
}