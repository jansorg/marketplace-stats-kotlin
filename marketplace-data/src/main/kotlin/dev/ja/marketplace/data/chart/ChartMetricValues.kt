/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.chart

import dev.ja.marketplace.churn.ChurnValidities
import dev.ja.marketplace.churn.isCountedByChurn
import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.client.model.DownloadRequestDimension
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.data.PercentageValue
import dev.ja.marketplace.data.offers.OfferDiscounts
import dev.ja.marketplace.data.subscriptions.CustomerRecurringRevenue
import dev.ja.marketplace.data.subscriptions.SubscriptionDailyValues
import dev.ja.marketplace.data.trackers.RecurringRevenueSales
import dev.ja.marketplace.data.trackers.SimpleTrialTracker
import dev.ja.marketplace.data.trackers.getResultWithinConversionPeriod
import dev.ja.marketplace.data.trackers.trialConversionDays
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.javamoney.moneta.FastMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import javax.money.MonetaryAmount

/**
 * The values of the [ChartMetric]s for each day, from the first year with data until today, for the charts.
 * The values of a metric and a window are calculated when they're requested first, see [series].
 *
 * The values have the meaning and the scale of the values of the tables, at the end of a period they're the values of
 * the table of the period, see [ChartWindow]:
 * - Sums, e.g. of sales, downloads and trials, are the sums of the window of each day.
 * - Licenses, MRR and ARR are the values at each day, like the values at the end of each month in the tables.
 *   They use the rules of [dev.ja.marketplace.data.trackers.LicenseTracker] and
 *   [dev.ja.marketplace.data.trackers.RecurringRevenueTracker].
 * - The churn is the share of the paid licenses, which were active at the day before the window and aren't active at
 *   the day, of the annual or monthly licenses, or of the licenses of all periods for the combined churn.
 * - New customers and new trial users are the customers of the window, whose first paid license or first trial of
 *   the plugin is in it, see [newCustomerDays]. New licenses are the paid licenses, whose first sale is in the
 *   window, see [newLicenseDays].
 * - The conversion rate of the trials, which started in the window: the share of the trials, which were converted by a
 *   new paid license within [trialConversionDays] days.
 * - The movements of the MRR and the retention compare the MRR of the customers at the day with the MRR at the day
 *   before the window, see [SubscriptionDailyValues].
 * - The shares of the customer types are the shares of the sales of the window.
 *
 * @param licensesOfPlugins The licenses of each plugin, several for the summary of all plugins.
 * @param revenue The recurring revenue of the customers of a single plugin, required for the subscription metrics.
 * @param metrics The metrics with values, e.g. only the metrics of the summary of all plugins. The metrics of
 *  [families] with values, which are known with the data, e.g. of the discounts of the sales, are part of it.
 * @param families The families of metrics with parameters, see [ChartMetricFamily]. The metrics of the downloads of
 *  releases and products are loaded with the [source] when they're requested, see [prepare].
 * @param dataStart The first day with data, sums and rates of windows, which start before it, are partial.
 */
class ChartMetricValues private constructor(
    private val windows: DailyWindows,
    private val sales: List<PluginSale>,
    private val licensesOfPlugins: List<List<LicenseInfo>>,
    private val trials: List<PluginTrial>,
    private val downloads: List<DailyDownload>,
    private val revenue: CustomerRecurringRevenue?,
    private val exchangeRates: ExchangeRates,
    private val maxTrialDays: Int,
    val metrics: Set<ChartMetric>,
    val families: Set<ChartMetricFamily>,
    private val source: ChartDownloadsSource?,
    private val dataStart: YearMonthDay?,
) {
    val days: List<YearMonthDay> get() = windows.days

    private val today = windows.today
    private val dayCount = windows.dayCount
    /** The currency code of the amounts. */
    val currency: String = exchangeRates.targetCurrency.currencyCode
    private val zero: MonetaryAmount = FastMoney.zero(exchangeRates.targetCurrency)

    private val cache = ConcurrentHashMap<Pair<ChartMetric, ChartWindow>, DailySeries>()

    /**
     * @return The values of the [metric] at each day of [days] for the [window], or `null` if there are no values of
     *  the metric, see [metrics]. The window is ignored by metrics, which don't use it, see [ChartMetric.usesWindow].
     */
    fun series(metric: ChartMetric, window: ChartWindow): DailySeries? {
        if (!isAvailable(metric) || days.isEmpty()) {
            return null
        }
        // values at each day are the same for all windows
        val key = metric to if (metric.usesWindow) window else ChartWindow.CalendarMonth
        cache[key]?.let { return it }
        // concurrent requests of the same values wait for the first one
        synchronized(this) {
            cache[key]?.let { return it }
            // the subscription metrics are calculated together, the values of the other metrics are added to the cache
            calculate(metric, key.second).forEach { (calculatedMetric, series) ->
                val partialUntil = if (calculatedMetric.usesWindow) partialUntil { key.second.start(it) } else 0
                cache.putIfAbsent(calculatedMetric to key.second, series.copy(partialUntil = partialUntil))
            }
            return cache[key]
        }
    }

    /**
     * @return If the values of the metric can be calculated: a metric of [metrics] or a metric of the downloads of a
     *  release or product, whose values are loaded by [prepare].
     */
    fun isAvailable(metric: ChartMetric): Boolean {
        return metric in metrics || metric.family in families && (metric.family in LOADED_FAMILIES || metric.family in UNIQUE_FAMILIES)
    }

    /**
     * The downloads of each day from [from] of a metric of [LOADED_FAMILIES], see [prepare], [byMonth] if they're the
     * downloads of each month, see [ChartDownloadsSource.downloads].
     */
    private class LoadedDownloads(val from: YearMonthDay, val perDay: LongArray, val byMonth: Boolean)

    /** The loaded downloads by the ID of the metric, see [prepare]. */
    private val loadedDownloads = ConcurrentHashMap<String, LoadedDownloads>()

    /** The unique downloads of each period from [from] by its first day, see [ChartDownloadsSource.uniqueDownloads]. */
    private class LoadedUniqueDownloads(val from: YearMonthDay, val values: Map<YearMonthDay, Long>)

    /** The loaded unique downloads by the ID of the metric and the period, see [prepare]. */
    private val loadedUnique = ConcurrentHashMap<Pair<String, DownloadRequestDimension>, LoadedUniqueDownloads>()

    /** The families and indexes of the parameters, whose labels were loaded, see [parameterValues]. */
    private val loadedLabels = ConcurrentHashMap.newKeySet<Pair<ChartMetricFamily, Int>>()

    /**
     * Loads the data of the metrics, which aren't calculated from the data of the plugin, e.g. the downloads of a
     * release, and the labels of their values, e.g. the version of a release. It must be called before the values of
     * these metrics are requested, other metrics are ignored.
     *
     * @param from The first day, whose data is loaded, e.g. of the shown dates of a chart, see `ChartData.loadStart`.
     *  The windows, which start before it, have no values. The downloads of a release in a product take long to load.
     *  An earlier day than before loads the data again from this day, the parts, which were loaded before, are cached
     *  by the client. `null` for all days.
     * @param byMonth If the downloads of each month are enough, which are faster to load, see
     *  [ChartDownloadsSource.downloads], e.g. for the calendar months of the level Month. Downloads, which were loaded
     *  by month, are loaded again by day, when they're needed by day.
     * @param windows The windows of the values, the unique downloads are loaded for the periods of these windows, see
     *  [ChartMetricFamily.UNIQUE_DOWNLOADS_WINDOWS].
     */
    suspend fun prepare(
        metrics: Collection<ChartMetric>,
        from: YearMonthDay? = null,
        byMonth: Boolean = false,
        windows: Collection<ChartWindow> = ChartMetricFamily.UNIQUE_DOWNLOADS_WINDOWS,
    ) {
        val source = source ?: return
        val firstDay = this.windows.days.firstOrNull() ?: return
        val start = maxOf(firstDay, from ?: firstDay)
        for (metric in metrics.distinct()) {
            val family = metric.family
            if (family in UNIQUE_FAMILIES && family in families) {
                windows.mapNotNull(::uniquePeriod).distinct().forEach { prepareUnique(source, metric, start, it) }
                continue
            }
            val loaded = loadedDownloads[metric.id]
            // the downloads of each day are exact for all requests
            if (family !in LOADED_FAMILIES || family !in families || (loaded != null && loaded.from <= start && (byMonth || !loaded.byMonth))) {
                continue
            }
            val values = metric.parameterValues
            val (release, product) = when (family) {
                ChartMetricFamily.ReleaseDownloads -> values[0]!!.toInt() to values[1]
                else -> null to values[0]
            }
            // a line, whose downloads couldn't be loaded, has no values, the other lines are shown
            val downloads = try {
                source.downloads(release, product, start, byMonth)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Loading the downloads of the chart metric ${metric.id} failed", e)
                continue
            }
            loadedDownloads[metric.id] = LoadedDownloads(start, this.windows.countsPerDay(downloads.map { it.day to it.downloads }), byMonth)
            // the values of the downloads, which were loaded from a later day or by month before
            cache.keys.removeIf { it.first == metric }
            averages.keys.removeIf { it.first == metric }
            loadLabels(metric)
        }
    }

    private suspend fun prepareUnique(source: ChartDownloadsSource, metric: ChartMetric, start: YearMonthDay, period: DownloadRequestDimension) {
        val key = metric.id to period
        if (loadedUnique[key]?.from?.let { it <= start } == true) {
            return
        }
        val values = metric.parameterValues
        val (release, product) = when (metric.family) {
            ChartMetricFamily.ReleaseUniqueDownloads -> values[0]!!.toInt() to values[1]
            ChartMetricFamily.ProductUniqueDownloads -> null to values[0]
            else -> null to null
        }
        // a line, whose downloads couldn't be loaded, has no values, the other lines are shown
        val downloads = try {
            source.uniqueDownloads(release, product, start, period)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Loading the unique downloads of the chart metric ${metric.id} failed", e)
            return
        }
        loadedUnique[key] = LoadedUniqueDownloads(start, downloads.associate { it.day to it.downloads })
        cache.keys.removeIf { it.first == metric }
        averages.keys.removeIf { it.first == metric }
        loadLabels(metric)
    }

    /** Loads the labels of the values of a metric of a family, the title of the metric contains them. */
    private suspend fun loadLabels(metric: ChartMetric) {
        val family = metric.family ?: return
        family.parameters.indices.filter { metric.parameterValues[it] != null }.forEach { index ->
            if (Pair(family, index) !in loadedLabels) {
                parameterValues(family, index)
            }
        }
    }

    /**
     * @return The values of a parameter of a family of metrics, e.g. the releases, and registers their labels, see
     *  [ChartMetricParameter.registerLabel]. Families without values for the plugin have none.
     */
    suspend fun parameterValues(family: ChartMetricFamily, index: Int): List<ChartParameterValue> {
        if (family !in families || index !in family.parameters.indices) {
            return emptyList()
        }
        val values = when (family) {
            // the share of unknown customer types is no row of the table
            ChartMetricFamily.CustomerTypeShare -> CustomerType.entries.filter { it != CustomerType.Unknown }.map { ChartParameterValue(it.toString(), it.toString()) }
            ChartMetricFamily.Discount -> metrics.mapNotNull { metric ->
                metric.discountName?.let { ChartParameterValue(metric.parameterValues[0]!!, it) }
            }

            ChartMetricFamily.ReleaseDownloads, ChartMetricFamily.ReleaseUniqueDownloads -> if (index == 0) source?.releases() else source?.products()
            ChartMetricFamily.ProductDownloads, ChartMetricFamily.ProductUniqueDownloads -> source?.products()
            else -> null
        } ?: emptyList()
        values.forEach { family.parameters[index].registerLabel(it.value, it.label) }
        loadedLabels += family to index
        return values
    }

    private val averages = ConcurrentHashMap<Triple<ChartMetric, ChartWindow, Int>, DailySeries>()

    /**
     * @return The moving average of the [metric] over the [months] before each day, on the scale of the [window], or
     *  `null` if it doesn't apply, see [averageApplies]. The windows, which end at the day and at the day before each
     *  previous window, fill the months, e.g. the 3 months before the day for a window of a month:
     *  - Sums are the sum of the windows divided by their number, e.g. the average month or the average day.
     *  - Rates are the rates of the pooled numerators and denominators of the windows, e.g. the churned licenses of
     *    the windows of the months divided by the licenses at the start of the windows.
     *  - Values at each day are the average of the values of the days.
     *
     *  Calendar windows use their rolling windows, at the end of a period they're the same. There's no average, if the
     *  months start before the first day.
     */
    fun average(metric: ChartMetric, window: ChartWindow, months: Int): DailySeries? {
        if (!averageApplies(metric, window, months)) {
            return null
        }
        val tileWindow = if (metric.usesWindow) window.rolling else ChartWindow.Day
        val key = Triple(metric, tileWindow, months)
        averages[key]?.let { return it }
        val base = series(metric, tileWindow) ?: return null
        synchronized(averages) {
            averages[key]?.let { return it }
            return calculateAverage(metric, tileWindow, months, base).also { averages[key] = it }
        }
    }

    private fun calculateAverage(metric: ChartMetric, tileWindow: ChartWindow, months: Int, base: DailySeries): DailySeries {
        val starts = windows.starts(tileWindow)
        val ratio = base.ratio
        val values = List(dayCount) { index ->
            val spanStart = windows.index(ChartWindow.monthsBefore(days[index], months))
            if (spanStart < 0) {
                return@List null
            }
            // the days of the windows, which fill the months
            var sum = BigDecimal.ZERO
            var numerators = BigDecimal.ZERO
            var denominators = BigDecimal.ZERO
            var count = 0
            var windowCount = 0
            var end = index
            while (end >= spanStart) {
                val start = starts[end]
                // the window of the day is always used, also if it's longer than the months, e.g. the 12 months
                // before the day for a 3-month average, the average is then the value of the day
                if (windowCount > 0 && (start < spanStart || (start == 0 && tileWindow.start(days[end]) < days.first()))) {
                    break
                }
                windowCount++
                if (ratio != null) {
                    // e.g. no retention without the recurring revenue at the start of the window
                    numerators += ratio.numerators[end] ?: return@List null
                    denominators += ratio.denominators[end] ?: return@List null
                } else {
                    // values at each day without a value, e.g. the ARPA without customers, aren't counted
                    val value = base.values[end]
                    if (value != null) {
                        sum += value
                        count++
                    } else if (metric.usesWindow) {
                        return@List null
                    }
                }
                end = start - 1
            }
            when {
                ratio != null -> when (denominators.signum()) {
                    0 -> null
                    else -> numerators.multiply(ratio.factor).divide(denominators, 2, RoundingMode.HALF_UP)
                }

                count == 0 -> null
                else -> sum.divide(BigDecimal.valueOf(count.toLong()), 2, RoundingMode.HALF_UP)
            }
        }
        // the averages of sums and rates, whose months start before the first day with data
        val partialUntil = if (metric.usesWindow) partialUntil { ChartWindow.monthsBefore(it, months) } else 0
        return DailySeries(base.unit, values, base.incompleteFrom, partialUntil = partialUntil)
    }

    /**
     * @param start The first day of the window of a day.
     * @return The index of the first day, whose window doesn't start before the first day with data. The values of the
     *  days before only cover a part of their window, e.g. the first year of a plugin, which started in February.
     *  The windows start later for later days.
     */
    private fun partialUntil(start: (YearMonthDay) -> YearMonthDay): Int {
        val first = dataStart ?: return 0
        return days.indexOfFirst { start(it) >= first }.takeIf { it >= 0 } ?: days.size
    }

    private val salesAndFees: Pair<Array<MonetaryAmount>, Array<MonetaryAmount>> by lazy { windows.salesAndFeesPerDay(sales, exchangeRates) }
    private val downloadsPerDay: LongArray by lazy { windows.countsPerDay(downloads.map { it.day to it.downloads }) }
    private val trialsPerDay: LongArray by lazy { windows.countsPerDay(trials.map { it.date to 1L }) }
    /** The first trial of each customer. */
    private val newTrialUsersPerDay: LongArray by lazy {
        windows.countsPerDay(trials.groupBy { it.customer.code }.values.map { customerTrials -> customerTrials.minOf { it.date } to 1L })
    }
    /** The licenses of each plugin are counted separately, the IDs of licenses of different plugins could be the same. */
    private val newLicensesPerDay: LongArray by lazy {
        windows.countsPerDay(licensesOfPlugins.flatMap { licenses -> newLicenseDays(licenses).map { it to 1L } })
    }
    /** The customers of each plugin are counted separately, like the licenses of the churn. */
    private val newCustomersPerDay: LongArray by lazy {
        windows.countsPerDay(licensesOfPlugins.flatMap { licenses -> newCustomerDays(licenses).map { it to 1L } })
    }
    private val allLicenses: List<LicenseInfo> by lazy { licensesOfPlugins.flatten() }
    /** The validities of the licenses of each plugin, the IDs of the licenses of different plugins could be the same. */
    private val churnValidities: List<ChurnValidities> by lazy { licensesOfPlugins.map(::ChurnValidities) }
    private val trialConversions: Pair<Set<PluginTrial>, YearMonthDay?> by lazy { trialConversions(trials, sales, maxTrialDays, today) }
    private val recurringRevenue: Pair<DailySeries, DailySeries> by lazy { recurringRevenue() }

    private fun calculate(metric: ChartMetric, window: ChartWindow): Map<ChartMetric, DailySeries> {
        val starts = windows.starts(window)
        if (metric.family in UNIQUE_FAMILIES) {
            return uniqueSeries(metric, window)?.let { mapOf(metric to it) } ?: emptyMap()
        }
        if (metric.family in LOADED_FAMILIES) {
            // without prepare, the downloads of the metric aren't known
            val loaded = loadedDownloads[metric.id] ?: return emptyMap()
            val sums = windows.windowSums(loaded.perDay, starts)
            // the windows, which start before the loaded downloads, have no value
            val firstIndex = windows.index(loaded.from)
            val values = sums.mapIndexed { index, sum -> if (starts[index] < firstIndex) null else BigDecimal.valueOf(sum) }
            return mapOf(metric to DailySeries(ChartUnit.Count.id(currency), values, windows.incompleteSums))
        }
        val series = when (metric) {
            ChartMetric.Sales -> amountSeries(windows.windowSums(salesAndFees.first, starts), windows.incompleteSums)
            ChartMetric.Fees -> amountSeries(windows.windowSums(salesAndFees.second, starts), windows.incompleteSums)
            ChartMetric.Invoice -> {
                val totals = windows.windowSums(salesAndFees.first, starts)
                val fees = windows.windowSums(salesAndFees.second, starts)
                amountSeries(totals.mapIndexed { index, total -> total - fees[index] }, windows.incompleteSums)
            }

            ChartMetric.Downloads -> countSeries(windows.windowSums(downloadsPerDay, starts))
            ChartMetric.Trials -> countSeries(windows.windowSums(trialsPerDay, starts))
            ChartMetric.NewTrialUsers -> countSeries(windows.windowSums(newTrialUsersPerDay, starts))
            ChartMetric.NewCustomers -> countSeries(windows.windowSums(newCustomersPerDay, starts))
            ChartMetric.TrialConversion -> conversionRates(starts)
            ChartMetric.Licenses -> licenseCount(allLicenses.filter { it.validity != null })
            ChartMetric.PaidLicenses -> licenseCount(allLicenses.filter { it.validity != null && it.isPaidLicense })
            ChartMetric.NewLicenses -> countSeries(windows.windowSums(newLicensesPerDay, starts))
            ChartMetric.Mrr -> recurringRevenue.first
            ChartMetric.Arr -> recurringRevenue.second
            ChartMetric.ChurnAnnual -> churn(LicensePeriod.Annual, window)
            ChartMetric.ChurnMonthly -> churn(LicensePeriod.Monthly, window)
            ChartMetric.ChurnCombined -> churn(null, window)
            in customerTypeShares -> customerTypeShare(customerTypeShares.getValue(metric), starts)
            in discountMetrics -> amountSeries(windows.windowSums(discountsPerDay(metric.discountName!!), starts), windows.incompleteSums)
            // the movements of the MRR, the retention and the average revenue of the customers
            // the movements of the MRR of the window, or the values at each day
            else -> return SubscriptionDailyValues.create(days, allLicenses, revenue ?: return emptyMap(), today, window.takeIf { metric.usesWindow })
        }
        return mapOf(metric to series)
    }

    /**
     * @return The unique downloads of the [window] at each day, `null` for a window, which the statistics service has
     *  no period of: at each day for [ChartWindow.Day], at each Sunday and today for [ChartWindow.Week], the calendar
     *  week from Monday, and at the last day of each month and today for [ChartWindow.CalendarMonth]. The other days
     *  and the periods before the loaded downloads have no value, the unique downloads can't be summed.
     */
    private fun uniqueSeries(metric: ChartMetric, window: ChartWindow): DailySeries? {
        val period = uniquePeriod(window) ?: return null
        val loaded = loadedUnique[metric.id to period] ?: return null
        val values = days.map { day ->
            val periodStart = when (period) {
                DownloadRequestDimension.Week -> day.takeIf { it == today || it.toLocalDate().dayOfWeek == DayOfWeek.SUNDAY }
                    ?.let { it.add(0, 0, 1 - it.toLocalDate().dayOfWeek.value) }

                DownloadRequestDimension.Month -> day.takeIf { it == today || it == YearMonthDay.lastOfMonth(it.year, it.month) }?.atFirstOfMonth()
                else -> day
            }
            periodStart?.takeIf { day >= loaded.from }?.let { BigDecimal.valueOf(loaded.values[it] ?: 0) }
        }
        return DailySeries(ChartUnit.Count.id(currency), values, windows.incompleteSums)
    }

    /** The metrics of the discounts in the sales, see [ChartMetric.discount]. */
    private val discountMetrics: Set<ChartMetric> get() = metrics.filterTo(HashSet()) { it.discountName != null }

    /**
     * @return The amounts given away by the discount at each day, calculated from the paid amount and the percent of
     *  the discount of each license like the table of the offers, see [OfferDiscounts.lostAmount].
     */
    private fun discountsPerDay(discountName: String): Array<MonetaryAmount> {
        val amounts = Array(dayCount) { zero }
        for (license in allLicenses) {
            val index = windows.index(license.sale.date)
            if (index !in 0 until dayCount) {
                continue
            }
            for (discount in license.saleLineItem.discountDescriptions) {
                if (OfferDiscounts.discountName(discount) != discountName) {
                    continue
                }
                val lostUSD = OfferDiscounts.lostAmount(license.amountUSD, discount.percent) ?: continue
                val lost = OfferDiscounts.lostAmount(license.amount, discount.percent) ?: continue
                amounts[index] += exchangeRates.targetAmount(license.sale.date, lostUSD, lost)
            }
        }
        return amounts
    }

    private fun amountSeries(values: List<MonetaryAmount>, incompleteFrom: Int): DailySeries {
        return DailySeries(currency, DailyWindows.amounts(values), incompleteFrom)
    }

    private fun countSeries(sums: List<Long>): DailySeries {
        return DailySeries(ChartUnit.Count.id(currency), sums.map(BigDecimal::valueOf), windows.incompleteSums)
    }

    /** @return The number of licenses, which are valid at each day. Licenses with several sales are counted once. */
    private fun licenseCount(licenses: List<LicenseInfo>): DailySeries {
        val changes = IntArray(dayCount + 1)
        for (validities in licenses.groupBy({ it.id }, { it.validity!! }).values) {
            for ((start, end) in mergeRanges(validities)) {
                addToDays(start, end) { from, to ->
                    changes[from]++
                    changes[to + 1]--
                }
            }
        }
        var count = 0
        val counts = List(dayCount) { index ->
            count += changes[index]
            BigDecimal.valueOf(count.toLong())
        }
        return DailySeries(ChartUnit.Count.id(currency), counts, dayCount)
    }

    private fun mergeRanges(ranges: List<YearMonthDayRange>): List<Pair<YearMonthDay, YearMonthDay>> {
        val result = mutableListOf<Pair<YearMonthDay, YearMonthDay>>()
        for (range in ranges.sortedBy { it.start }) {
            val last = result.lastOrNull()
            if (last != null && range.start <= last.second.add(0, 0, 1)) {
                result[result.lastIndex] = last.first to maxOf(last.second, range.end)
            } else {
                result += range.start to range.end
            }
        }
        return result
    }

    /** Calls [action] with the indices of the days from [start] to [end], which are within the days. */
    private inline fun addToDays(start: YearMonthDay, end: YearMonthDay, action: (Int, Int) -> Unit) {
        val from = maxOf(0, windows.index(start))
        val to = minOf(dayCount - 1, windows.index(end))
        if (from <= to) {
            action(from, to)
        }
    }

    /**
     * @return The churn rate in percent at each day, like [dev.ja.marketplace.churn.LicenseChurnProcessor]: the
     * share of the paid licenses of the [period], which were active at the day before the window, and which aren't
     * active at the day, e.g. not renewed within the grace period. `null` without licenses at the previous day.
     * The rates of the current period of the window are incomplete, licenses expiring later may still be renewed.
     *
     * @param period The period of the licenses, `null` for the combined churn of the licenses of all periods.
     */
    private fun churn(period: LicensePeriod?, window: ChartWindow): DailySeries {
        val previousDays = windows.days.map { window.start(it).add(0, 0, -1) }
        val previousChanges = IntArray(dayCount + 1)
        val churnedChanges = IntArray(dayCount + 1)
        // the licenses of each plugin, the IDs of the licenses of different plugins could be the same
        val licensesById = licensesOfPlugins.zip(churnValidities).flatMap { (licenses, validities) ->
            licenses.groupBy { it.id }.values.map { it to validities }
        }
        for ((sales, validities) in licensesById) {
            val accepted = mergeRanges(sales.filter { it.isCountedByChurn(period) }.mapNotNull(validities::validity))
            if (accepted.isEmpty()) {
                continue
            }
            val active = mergeRanges(sales.mapNotNull(validities::validity))
            for ((start, end) in accepted) {
                // the days, whose previous day is within the range, the previous days are ordered
                val from = lowerBound(previousDays, start)
                val to = lowerBound(previousDays, end.add(0, 0, 1)) - 1
                if (from > to) {
                    continue
                }
                previousChanges[from]++
                previousChanges[to + 1]--
                churnedChanges[from]++
                churnedChanges[to + 1]--
                // not churned at the days, at which the license is active
                for ((activeStart, activeEnd) in active) {
                    val activeFrom = maxOf(from, windows.index(activeStart))
                    val activeTo = minOf(to, windows.index(activeEnd))
                    if (activeFrom <= activeTo) {
                        churnedChanges[activeFrom]--
                        churnedChanges[activeTo + 1]++
                    }
                }
            }
        }

        var previousCount = 0
        var churnedCount = 0
        val previous = ArrayList<BigDecimal?>(dayCount)
        val churned = ArrayList<BigDecimal?>(dayCount)
        val rates = List(dayCount) { index ->
            previousCount += previousChanges[index]
            churnedCount += churnedChanges[index]
            previous += BigDecimal.valueOf(previousCount.toLong())
            churned += BigDecimal.valueOf(churnedCount.toLong())
            PercentageValue.rateOf(churnedCount, previousCount)?.value?.setScale(2, RoundingMode.HALF_UP)
        }
        val incompleteFrom = maxOf(0, windows.index(window.currentPeriodStart(today)))
        return DailySeries("%", rates, incompleteFrom, DailyRatio(churned, previous, HUNDRED))
    }

    /**
     * @return The share of the converted trials of the trials, which started in the window of each day, in percent.
     *  `null` without trials. Values with trials, which can still be converted, are incomplete.
     */
    private fun conversionRates(starts: IntArray): DailySeries {
        val (converted, firstPending) = trialConversions
        val totals = windows.windowSums(trialsPerDay, starts)
        val conversions = windows.windowSums(windows.countsPerDay(trials.filter { it in converted }.map { it.date to 1L }), starts)
        val rates = totals.mapIndexed { index, total ->
            PercentageValue.rateOf(BigDecimal.valueOf(conversions[index]), BigDecimal.valueOf(total))?.value?.setScale(2, RoundingMode.HALF_UP)
        }
        val incompleteFrom = firstPending?.let { maxOf(0, windows.index(it)) }?.coerceAtMost(dayCount) ?: dayCount
        val ratio = DailyRatio(conversions.map(BigDecimal::valueOf), totals.map(BigDecimal::valueOf), HUNDRED)
        return DailySeries("%", rates, incompleteFrom, ratio)
    }

    /** @return The index of the first day, which isn't before [day]. */
    private fun lowerBound(days: List<YearMonthDay>, day: YearMonthDay): Int {
        var low = 0
        var high = days.size
        while (low < high) {
            val middle = (low + high) / 2
            if (days[middle] < day) low = middle + 1 else high = middle
        }
        return low
    }

    /**
     * @return The MRR and the ARR at each day, the sums of the plugins. Only paid subscription licenses contribute and
     * only the latest sale of each license, which is valid at the day.
     */
    private fun recurringRevenue(): Pair<DailySeries, DailySeries> {
        val mrrChanges = Array(dayCount + 1) { zero }
        val arrChanges = Array(dayCount + 1) { zero }
        for (licenses in licensesOfPlugins) {
            val salesByLicense = licenses
                .filter(RecurringRevenueSales::isRecurring)
                .groupBy { it.id }
            for (licenseSales in salesByLicense.values) {
                // the latest sale only changes at the start or after the end of a sale
                val boundaries = licenseSales
                    .flatMap { listOf(it.validity!!.start, it.validity!!.end.add(0, 0, 1)) }
                    .distinct()
                    .sorted()
                for ((segmentStart, nextStart) in boundaries.zipWithNext()) {
                    // the sale, which counts for the recurring revenue, like in RecurringRevenueTracker
                    val latest = RecurringRevenueSales.latest(licenseSales.filter { segmentStart in it.validity!! }) ?: continue

                    val mrrFactor = RecurringRevenueSales.monthlyFactor(latest.sale.licensePeriod)
                    val arrFactor = RecurringRevenueSales.annualFactor(latest.sale.licensePeriod)
                    val mrr = exchangeRates.targetAmount(latest.sale.date, latest.amountUSD * mrrFactor, latest.amount * mrrFactor)
                    val arr = exchangeRates.targetAmount(latest.sale.date, latest.amountUSD * arrFactor, latest.amount * arrFactor)
                    addToDays(segmentStart, nextStart.add(0, 0, -1)) { from, to ->
                        mrrChanges[from] += mrr
                        mrrChanges[to + 1] -= mrr
                        arrChanges[from] += arr
                        arrChanges[to + 1] -= arr
                    }
                }
            }
        }

        // the recurring revenue of the current month is incomplete like in the tables,
        // it's the value of today, it changes until the end of the month
        val mrr = windows.runningSums(mrrChanges.copyOf(dayCount).requireNoNulls())
        val arr = windows.runningSums(arrChanges.copyOf(dayCount).requireNoNulls())
        return amountSeries(mrr.toList(), windows.incompleteRecurringRevenue) to amountSeries(arr.toList(), windows.incompleteRecurringRevenue)
    }

    /**
     * @return The share of the sales of the [type] of customers of the sales in the window of each day, in percent.
     *  Days without sales in the window have no value.
     */
    private fun customerTypeShare(type: CustomerType, starts: IntArray): DailySeries {
        val totals = Array<BigDecimal>(dayCount) { BigDecimal.ZERO }
        val ofType = Array<BigDecimal>(dayCount) { BigDecimal.ZERO }
        for (sale in sales) {
            val index = windows.index(sale.date)
            if (index in 0 until dayCount) {
                val amount = exchangeRates.targetAmount(sale.date, sale.amountUSD, sale.amount).number.numberValue(BigDecimal::class.java)
                totals[index] += amount
                if (sale.customer.type == type) {
                    ofType[index] += amount
                }
            }
        }
        val windowTotals = windows.windowSums(totals, starts)
        val windowSums = windows.windowSums(ofType, starts)
        val shares = windowTotals.mapIndexed { index, total ->
            when (total.signum()) {
                0 -> null
                else -> windowSums[index].multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP)
            }
        }
        return DailySeries("%", shares, windows.incompleteSums, DailyRatio(windowSums, windowTotals, HUNDRED))
    }

    companion object {
        private val HUNDRED = BigDecimal(100)

        /** The months of the moving averages. */
        val AVERAGE_MONTHS = setOf(1, 3, 12)

        /**
         * @return If there's a moving average of the [months]. All metrics and windows have averages, the average of
         *  sums and rates of windows, which aren't shorter than the months, is the value of each day.
         */
        @Suppress("UNUSED_PARAMETER")
        fun averageApplies(metric: ChartMetric, window: ChartWindow, months: Int): Boolean {
            return months in AVERAGE_MONTHS
        }

        private val customerTypeShares = CustomerType.entries.associateBy(ChartMetric::customerTypeShare)

        /** The families, whose data is loaded when their metrics are requested, see [prepare]. */
        private val LOADED_FAMILIES = setOf(ChartMetricFamily.ReleaseDownloads, ChartMetricFamily.ProductDownloads)

        /** The families of the unique downloads, which are loaded for each period, see [uniqueSeries]. */
        private val UNIQUE_FAMILIES = setOf(ChartMetricFamily.UniqueDownloads, ChartMetricFamily.ReleaseUniqueDownloads, ChartMetricFamily.ProductUniqueDownloads)

        /** @return The period of the unique downloads of a window, `null` if the statistics service has no such period. */
        private fun uniquePeriod(window: ChartWindow): DownloadRequestDimension? = when (window) {
            ChartWindow.Day -> DownloadRequestDimension.Day
            ChartWindow.Week -> DownloadRequestDimension.Week
            ChartWindow.CalendarMonth -> DownloadRequestDimension.Month
            else -> null
        }

        /** The metrics of the summary of all plugins. */
        private val allPluginsMetrics = setOf(
            ChartMetric.Sales, ChartMetric.Fees, ChartMetric.Invoice, ChartMetric.Downloads, ChartMetric.Mrr, ChartMetric.Arr,
            ChartMetric.ChurnCombined, ChartMetric.NewCustomers, ChartMetric.NewLicenses,
        )

        /**
         * @return The metrics of the discount amounts of the offers and discounts, which the licenses used, sorted by
         *  their name. Discounts without a discount amount, e.g. free offers, have no metric, see [ChartMetric.discount].
         */
        fun discountMetrics(licenses: List<LicenseInfo>): List<ChartMetric> {
            return licenses.asSequence()
                .flatMap { license -> license.saleLineItem.discountDescriptions.filter { OfferDiscounts.lostAmount(license.amountUSD, it.percent) != null } }
                .mapNotNull(OfferDiscounts::discountName)
                .distinct()
                .sorted()
                .map(ChartMetric::discount)
                .toList()
        }

        /**
         * @return The values of a plugin, all metrics.
         */
        fun create(
            sales: List<PluginSale>,
            licenses: List<LicenseInfo>,
            trials: List<PluginTrial>,
            downloads: List<DailyDownload>,
            exchangeRates: ExchangeRates,
            today: YearMonthDay = YearMonthDay.now(),
            maxTrialDays: Int = Marketplace.MAX_TRIAL_DAYS_DEFAULT,
            revenue: CustomerRecurringRevenue? = null,
            source: ChartDownloadsSource? = null,
        ): ChartMetricValues {
            val discounts = discountMetrics(licenses)
            val metrics = ChartMetric.all.toSet() + customerTypeShares.keys + discounts
            val families = setOfNotNull(ChartMetricFamily.CustomerTypeShare, ChartMetricFamily.Discount.takeIf { discounts.isNotEmpty() }) +
                    if (source != null) LOADED_FAMILIES + UNIQUE_FAMILIES else emptySet()
            return create(sales, listOf(licenses), trials, downloads, revenue, exchangeRates, today, maxTrialDays, metrics, families, source)
        }

        /**
         * @return The values of a free plugin, which has no sales, licenses and trials: the metrics, which don't need
         *  them, see [ChartMetric.isPaidOnly].
         */
        fun freePlugin(
            downloads: List<DailyDownload>,
            exchangeRates: ExchangeRates,
            today: YearMonthDay = YearMonthDay.now(),
            source: ChartDownloadsSource? = null,
        ): ChartMetricValues {
            val metrics = ChartMetric.all.filterNot(ChartMetric::isPaidOnly).toSet()
            val families = if (source != null) (LOADED_FAMILIES + UNIQUE_FAMILIES).filterNot(ChartMetricFamily::isPaidOnly).toSet() else emptySet()
            return create(emptyList(), emptyList(), emptyList(), downloads, null, exchangeRates, today, Marketplace.MAX_TRIAL_DAYS_DEFAULT, metrics, families, source)
        }

        /**
         * @return The values of the summary of all paid plugins: sales, fees, invoices, downloads, MRR, ARR, the
         *  combined churn rate and the new customers and licenses, the sums of the new customers and licenses of each
         *  plugin.
         * @param licensesOfPlugins The licenses of each plugin.
         */
        fun allPlugins(
            sales: List<PluginSale>,
            downloads: List<DailyDownload>,
            licensesOfPlugins: List<List<LicenseInfo>>,
            exchangeRates: ExchangeRates,
            today: YearMonthDay = YearMonthDay.now(),
        ): ChartMetricValues {
            return create(
                sales, licensesOfPlugins, emptyList(), downloads, null, exchangeRates, today, Marketplace.MAX_TRIAL_DAYS_DEFAULT, allPluginsMetrics,
                emptySet(), null,
            )
        }

        private fun create(
            sales: List<PluginSale>,
            licensesOfPlugins: List<List<LicenseInfo>>,
            trials: List<PluginTrial>,
            downloads: List<DailyDownload>,
            revenue: CustomerRecurringRevenue?,
            exchangeRates: ExchangeRates,
            today: YearMonthDay,
            maxTrialDays: Int,
            metrics: Set<ChartMetric>,
            families: Set<ChartMetricFamily>,
            source: ChartDownloadsSource?,
        ): ChartMetricValues {
            val firstDate = listOfNotNull(
                sales.minOfOrNull { it.date },
                licensesOfPlugins.flatten().mapNotNull { it.validity?.start }.minOrNull(),
                trials.minOfOrNull { it.date },
                downloads.filter { it.downloads > 0 }.minOfOrNull { it.day },
            ).minOrNull()
            // the first year starts at January 1, so that the values of the windows of all years are complete
            val first = when {
                firstDate == null || firstDate > today -> today.add(0, 0, 1)
                else -> YearMonthDay(firstDate.year, 1, 1)
            }
            val windows = DailyWindows(first, today)
            return ChartMetricValues(windows, sales, licensesOfPlugins, trials, downloads, revenue, exchangeRates, maxTrialDays, metrics, families, source, firstDate)
        }

        /**
         * @return The day of the first paid license of each new customer of a plugin, by the ID of the customer:
         * - Only paid licenses count, a customer with only free licenses isn't a customer yet.
         * - A customer is new once, a customer who returns after the licenses ended isn't new again.
         * - A customer, who received a license from another customer (a license, which another customer bought
         *   before, see [dev.ja.marketplace.data.transfers.LicenseTransfers]) before or at the day of the first paid
         *   license, isn't new, also not by a later purchase of an own license. The relationship started with the
         *   transfer, e.g. an organization, which took over the license of an employee.
         * - Several licenses of a customer at the same day count once.
         */
        fun newCustomerDays(licenses: List<LicenseInfo>): List<YearMonthDay> {
            val firstTransfer = HashMap<CustomerId, YearMonthDay>()
            for (purchases in licenses.groupBy { it.id }.values) {
                sortedPurchases(purchases)
                    .zipWithNext()
                    .filter { (previous, next) -> previous.sale.customer.code != next.sale.customer.code }
                    .forEach { (_, next) -> firstTransfer.merge(next.sale.customer.code, next.sale.date, ::minOf) }
            }
            val firstPaid = HashMap<CustomerId, YearMonthDay>()
            for (license in licenses) {
                if (license.isPaidLicense) {
                    firstPaid.merge(license.sale.customer.code, license.sale.date, ::minOf)
                }
            }
            return firstPaid.mapNotNull { (customer, day) ->
                day.takeUnless { firstTransfer[customer]?.let { it <= day } == true }
            }
        }

        /**
         * @return The day of the first sale of each new paid license of a plugin, by the ID of the license:
         * - A license is new at its first purchase (by sale date, the order of
         *   [dev.ja.marketplace.data.transfers.LicenseTransfers]), if that purchase is paid. Renewals and other later purchases, e.g. a change of the period of the license,
         *   don't count, also not a free license, which is paid later.
         * - A license, which was transferred to another customer, is new once, at the purchase of the first
         *   customer. The purchases of the new owner, which follow the purchase of another customer, aren't new.
         * - Several licenses of a sale count each.
         */
        fun newLicenseDays(licenses: List<LicenseInfo>): List<YearMonthDay> {
            return licenses.groupBy { it.id }.values.mapNotNull { purchases ->
                sortedPurchases(purchases).first().takeIf { it.isPaidLicense }?.sale?.date
            }
        }

        /** @return The purchases of a license in the order of [dev.ja.marketplace.data.transfers.LicenseTransfers]. */
        private fun sortedPurchases(purchases: List<LicenseInfo>): List<LicenseInfo> {
            return purchases.sortedWith(compareBy({ it.sale.date }, { it.validity?.start }))
        }

        /**
         * @return The trials, which were converted within the conversion period, like the table of the overview, and the
         *  first day of the trials, which can still be converted.
         */
        private fun trialConversions(trials: List<PluginTrial>, sales: List<PluginSale>, maxTrialDays: Int, today: YearMonthDay): Pair<Set<PluginTrial>, YearMonthDay?> {
            val tracker = SimpleTrialTracker()
            tracker.init(trials)
            sales.forEach(tracker::processSale)
            val converted = tracker.getResultWithinConversionPeriod(YearMonthDayRange.MAX, maxTrialDays, today).convertedTrials.keys
            val conversionDays = trialConversionDays(maxTrialDays)
            val firstPending = trials.filter { it !in converted && it.date.daysUntil(today) <= conversionDays }.minOfOrNull { it.date }
            return converted to firstPending
        }
    }
}

private val logger = LoggerFactory.getLogger(ChartMetricValues::class.java)
