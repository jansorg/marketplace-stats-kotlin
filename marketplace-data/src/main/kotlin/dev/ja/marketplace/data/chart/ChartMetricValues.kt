/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.chart

import dev.ja.marketplace.churn.ChurnValidities
import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.data.PercentageValue
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
 *   the day.
 * - The conversion rate of the trials, which started in the window: the share of the trials, which were converted by a
 *   new paid license within [trialConversionDays] days.
 * - The movements of the MRR and the retention compare the MRR of the customers at the day with the MRR at the day
 *   before the window, see [SubscriptionDailyValues].
 * - The shares of the customer types are the shares of the sales of the window.
 *
 * @param licensesOfPlugins The licenses of each plugin, several for the summary of all plugins.
 * @param revenue The recurring revenue of the customers of a single plugin, required for the subscription metrics.
 * @param metrics The metrics with values, e.g. only the metrics of the summary of all plugins.
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
        if (metric !in metrics || days.isEmpty()) {
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
    private val allLicenses: List<LicenseInfo> by lazy { licensesOfPlugins.flatten() }
    private val churnValidities: ChurnValidities by lazy { ChurnValidities(allLicenses) }
    private val trialConversions: Pair<Set<PluginTrial>, YearMonthDay?> by lazy { trialConversions(trials, sales, maxTrialDays, today) }
    private val recurringRevenue: Pair<DailySeries, DailySeries> by lazy { recurringRevenue() }

    private fun calculate(metric: ChartMetric, window: ChartWindow): Map<ChartMetric, DailySeries> {
        val starts = windows.starts(window)
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
            ChartMetric.TrialConversion -> conversionRates(starts)
            ChartMetric.Licenses -> licenseCount(allLicenses.filter { it.validity != null })
            ChartMetric.PaidLicenses -> licenseCount(allLicenses.filter { it.validity != null && it.isPaidLicense })
            ChartMetric.Mrr -> recurringRevenue.first
            ChartMetric.Arr -> recurringRevenue.second
            ChartMetric.ChurnAnnual -> churn(LicensePeriod.Annual, window)
            ChartMetric.ChurnMonthly -> churn(LicensePeriod.Monthly, window)
            in customerTypeShares -> customerTypeShare(customerTypeShares.getValue(metric), starts)
            // the movements of the MRR, the retention and the average revenue of the customers
            // the movements of the MRR of the window, or the values at each day
            else -> return SubscriptionDailyValues.create(days, allLicenses, revenue ?: return emptyMap(), today, window.takeIf { metric.usesWindow })
        }
        return mapOf(metric to series)
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
     */
    private fun churn(period: LicensePeriod, window: ChartWindow): DailySeries {
        val previousDays = windows.days.map { window.start(it).add(0, 0, -1) }
        val previousChanges = IntArray(dayCount + 1)
        val churnedChanges = IntArray(dayCount + 1)
        for (sales in allLicenses.groupBy { it.id }.values) {
            val accepted = mergeRanges(sales.filter { it.isPaidLicense && it.sale.licensePeriod == period }.mapNotNull(churnValidities::validity))
            if (accepted.isEmpty()) {
                continue
            }
            val active = mergeRanges(sales.mapNotNull(churnValidities::validity))
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

        /** The metrics of the summary of all plugins. */
        private val allPluginsMetrics = setOf(
            ChartMetric.Sales, ChartMetric.Fees, ChartMetric.Invoice, ChartMetric.Downloads, ChartMetric.Mrr, ChartMetric.Arr,
        )

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
        ): ChartMetricValues {
            val metrics = ChartMetric.all.toSet()
            return create(sales, listOf(licenses), trials, downloads, revenue, exchangeRates, today, maxTrialDays, metrics)
        }

        /**
         * @return The values of the summary of all paid plugins: sales, fees, invoices, downloads, MRR and ARR.
         * @param licensesOfPlugins The licenses of each plugin.
         */
        fun allPlugins(
            sales: List<PluginSale>,
            downloads: List<DailyDownload>,
            licensesOfPlugins: List<List<LicenseInfo>>,
            exchangeRates: ExchangeRates,
            today: YearMonthDay = YearMonthDay.now(),
        ): ChartMetricValues {
            return create(sales, licensesOfPlugins, emptyList(), downloads, null, exchangeRates, today, Marketplace.MAX_TRIAL_DAYS_DEFAULT, allPluginsMetrics)
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
            return ChartMetricValues(windows, sales, licensesOfPlugins, trials, downloads, revenue, exchangeRates, maxTrialDays, metrics, firstDate)
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
