/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.minus
import dev.ja.marketplace.client.plus
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.data.overview.DailySeries
import dev.ja.marketplace.data.overview.OverviewDailyColumn
import dev.ja.marketplace.data.overview.OverviewDailyValues
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.javamoney.moneta.FastMoney
import java.math.BigDecimal
import java.math.RoundingMode
import javax.money.MonetaryAmount

/**
 * The values of [AllPluginsSummaryTable] and [AllPluginsMonthTable] for each day, for charts with a finer resolution.
 * The sums of the years are also used for the [YearlySummaryTable] of a single plugin.
 *
 * The sales and downloads of the summary of the years are the sums of the year before each day, i.e. from the day after
 * the same day of the previous year until the day. At the last day of a year, it's the sum of the calendar year like in
 * the table. The sums of [AllPluginsMonthTable] are the sums of the month before each day, like [OverviewDailyValues].
 * MRR and ARR are the sums of the values of the plugins at each day, see [OverviewDailyValues].
 *
 * @param days The days from the first year with data until today.
 * @param series The values of the columns by their ID, see [dev.ja.marketplace.data.DataTableColumn.dailyChartSeries].
 */
class AllPluginsDailyValues private constructor(val days: List<YearMonthDay>, val series: Map<String, DailySeries>) {
    companion object {
        const val SALES = "all-plugins-sales"
        const val FEES = "all-plugins-fees"
        const val PAID = "all-plugins-paid"
        const val DOWNLOADS = "all-plugins-downloads"
        const val MRR = "all-plugins-mrr"
        const val ARR = "all-plugins-arr"
        /** The trials of the year before each day, only if trials are passed to [create]. */
        const val TRIALS = "all-plugins-trials"

        /** The sums of the month before each day, for [AllPluginsMonthTable]. */
        const val MONTH_SALES = "all-plugins-month-sales"
        const val MONTH_FEES = "all-plugins-month-fees"
        const val MONTH_PAID = "all-plugins-month-paid"
        const val MONTH_DOWNLOADS = "all-plugins-month-downloads"

        /**
         * @param sales The sales of all paid plugins.
         * @param downloads The downloads of all paid plugins.
         * @param licensesOfPlugins The licenses of each paid plugin.
         * @param trials The trials, the series [TRIALS] is only added if there are trials.
         */
        fun create(
            sales: List<PluginSale>,
            downloads: List<DailyDownload>,
            licensesOfPlugins: List<List<LicenseInfo>>,
            exchangeRates: ExchangeRates,
            today: YearMonthDay = YearMonthDay.now(),
            trials: List<PluginTrial> = emptyList(),
        ): AllPluginsDailyValues {
            val firstDate = listOfNotNull(
                sales.minOfOrNull { it.date },
                licensesOfPlugins.flatten().mapNotNull { it.validity?.start }.minOrNull(),
                trials.minOfOrNull { it.date },
                downloads.filter { it.downloads > 0 }.minOfOrNull { it.day },
            ).minOrNull()
            if (firstDate == null || firstDate > today) {
                return AllPluginsDailyValues(emptyList(), emptyMap())
            }

            val first = YearMonthDay(firstDate.year, 1, 1)
            val dayCount = (first daysUntil today).toInt() + 1
            val days = List(dayCount) { first.add(0, 0, it) }
            fun index(date: YearMonthDay): Int = (first daysUntil date).toInt()

            // index of the first day of the year before each day
            val yearStartIndices = IntArray(dayCount) { index ->
                val day = days[index]
                val yearStart = when {
                    day.month == 12 && day.day == 31 -> YearMonthDay(day.year, 1, 1)
                    else -> day.add(-1, 0, 1)
                }
                maxOf(0, index(yearStart))
            }
            // index of the first day of the month before each day
            val monthStartIndices = IntArray(dayCount) { index ->
                val day = days[index]
                val monthStart = when (day) {
                    YearMonthDay.lastOfMonth(day.year, day.month) -> YearMonthDay.firstOfMonth(day.year, day.month)
                    else -> day.add(0, -1, 1)
                }
                maxOf(0, index(monthStart))
            }

            // the amounts in the displayed currency, like MonetaryAmountTracker
            val zero: MonetaryAmount = FastMoney.zero(exchangeRates.targetCurrency)
            fun convert(date: YearMonthDay, amountUSD: MonetaryAmount, amount: MonetaryAmount): MonetaryAmount {
                return when (exchangeRates.targetCurrency) {
                    MarketplaceCurrencies.USD -> amountUSD
                    amount.currency -> amount
                    else -> exchangeRates.convert(date, amount)
                }
            }

            val totals = Array(dayCount) { zero }
            val fees = Array(dayCount) { zero }
            for (sale in sales) {
                val index = index(sale.date)
                if (index in 0 until dayCount) {
                    totals[index] += convert(sale.date, sale.amountUSD, sale.amount)
                    fees[index] += convert(
                        sale.date,
                        Marketplace.feeAmount(sale.date, sale.amountUSD),
                        Marketplace.feeAmount(sale.date, sale.amount),
                    )
                }
            }
            val downloadCounts = LongArray(dayCount)
            for (download in downloads) {
                val index = index(download.day)
                if (index in 0 until dayCount) {
                    downloadCounts[index] += download.downloads
                }
            }
            val trialCounts = LongArray(dayCount)
            for (trial in trials) {
                val index = index(trial.date)
                if (index in 0 until dayCount) {
                    trialCounts[index]++
                }
            }

            for (index in 1 until dayCount) {
                totals[index] += totals[index - 1]
                fees[index] += fees[index - 1]
                downloadCounts[index] += downloadCounts[index - 1]
                trialCounts[index] += trialCounts[index - 1]
            }
            /** The sum from the start index of each day until the day, using the running sums. */
            fun <T> sumsOf(startIndices: IntArray, sums: (Int) -> T, minus: (T, T) -> T): List<T> {
                return List(dayCount) { index ->
                    val start = startIndices[index]
                    if (start == 0) sums(index) else minus(sums(index), sums(start - 1))
                }
            }

            val totalsOfYear = sumsOf(yearStartIndices, totals::get) { a, b -> a - b }
            val feesOfYear = sumsOf(yearStartIndices, fees::get) { a, b -> a - b }
            val downloadsOfYear = sumsOf(yearStartIndices, downloadCounts::get, Long::minus)
            val totalsOfMonth = sumsOf(monthStartIndices, totals::get) { a, b -> a - b }
            val feesOfMonth = sumsOf(monthStartIndices, fees::get) { a, b -> a - b }
            val downloadsOfMonth = sumsOf(monthStartIndices, downloadCounts::get, Long::minus)

            fun amounts(values: List<MonetaryAmount>): List<BigDecimal?> {
                return values.map { it.number.numberValue(BigDecimal::class.java).setScale(2, RoundingMode.HALF_UP) }
            }

            // the MRR and ARR of the plugins at each day
            val mrr = Array<BigDecimal>(dayCount) { BigDecimal.ZERO }
            val arr = Array<BigDecimal>(dayCount) { BigDecimal.ZERO }
            for (licenses in licensesOfPlugins) {
                val plugin = OverviewDailyValues.create(emptyList(), licenses, emptyList(), emptyList(), exchangeRates, today)
                val pluginMrr = plugin.series[OverviewDailyColumn.Mrr] ?: continue
                val pluginArr = plugin.series.getValue(OverviewDailyColumn.Arr)
                for ((pluginIndex, day) in plugin.days.withIndex()) {
                    val index = index(day)
                    if (index in 0 until dayCount) {
                        mrr[index] += pluginMrr.values[pluginIndex] ?: BigDecimal.ZERO
                        arr[index] += pluginArr.values[pluginIndex] ?: BigDecimal.ZERO
                    }
                }
            }

            // the sums of the current day may still change
            val incompleteFrom = dayCount - 1
            // the recurring revenue of the current month is incomplete like in the overview,
            // it's the value of today, it changes until the end of the month
            val incompleteRecurringRevenue = maxOf(0, index(YearMonthDay(today.year, today.month, 1)))
            val currency = exchangeRates.targetCurrency.currencyCode
            val trialSeries = when {
                trials.isEmpty() -> emptyMap()
                else -> {
                    val trialsOfYear = sumsOf(yearStartIndices, trialCounts::get, Long::minus)
                    mapOf(TRIALS to DailySeries("count", trialsOfYear.map(BigDecimal::valueOf), incompleteFrom))
                }
            }
            return AllPluginsDailyValues(
                days,
                trialSeries + mapOf(
                    SALES to DailySeries(currency, amounts(totalsOfYear), incompleteFrom),
                    FEES to DailySeries(currency, amounts(feesOfYear), incompleteFrom),
                    PAID to DailySeries(currency, amounts(totalsOfYear.zip(feesOfYear) { total, fee -> total - fee }), incompleteFrom),
                    DOWNLOADS to DailySeries("count", downloadsOfYear.map(BigDecimal::valueOf), incompleteFrom),
                    MRR to DailySeries(currency, mrr.toList(), incompleteRecurringRevenue),
                    ARR to DailySeries(currency, arr.toList(), incompleteRecurringRevenue),
                    MONTH_SALES to DailySeries(currency, amounts(totalsOfMonth), incompleteFrom),
                    MONTH_FEES to DailySeries(currency, amounts(feesOfMonth), incompleteFrom),
                    MONTH_PAID to DailySeries(currency, amounts(totalsOfMonth.zip(feesOfMonth) { total, fee -> total - fee }), incompleteFrom),
                    MONTH_DOWNLOADS to DailySeries("count", downloadsOfMonth.map(BigDecimal::valueOf), incompleteFrom),
                )
            )
        }
    }
}
