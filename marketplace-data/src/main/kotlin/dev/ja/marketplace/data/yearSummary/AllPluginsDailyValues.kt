/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.minus
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.data.overview.DailySeries
import dev.ja.marketplace.data.overview.DailyWindows
import dev.ja.marketplace.data.overview.OverviewDailyColumn
import dev.ja.marketplace.data.overview.OverviewDailyValues
import dev.ja.marketplace.exchangeRate.ExchangeRates
import java.math.BigDecimal

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

            val windows = DailyWindows(YearMonthDay(firstDate.year, 1, 1), today)
            val dayCount = windows.dayCount
            val days = windows.days

            val (totals, fees) = windows.salesAndFeesPerDay(sales, exchangeRates)
            val downloadCounts = windows.countsPerDay(downloads.map { it.day to it.downloads })
            val trialCounts = windows.countsPerDay(trials.map { it.date to 1L })

            val totalsOfYear = windows.windowSums(totals, windows.yearStarts)
            val feesOfYear = windows.windowSums(fees, windows.yearStarts)
            val downloadsOfYear = windows.windowSums(downloadCounts, windows.yearStarts)
            val totalsOfMonth = windows.windowSums(totals, windows.monthStarts)
            val feesOfMonth = windows.windowSums(fees, windows.monthStarts)
            val downloadsOfMonth = windows.windowSums(downloadCounts, windows.monthStarts)

            // the MRR and ARR of the plugins at each day
            val mrr = Array<BigDecimal>(dayCount) { BigDecimal.ZERO }
            val arr = Array<BigDecimal>(dayCount) { BigDecimal.ZERO }
            for (licenses in licensesOfPlugins) {
                val plugin = OverviewDailyValues.create(emptyList(), licenses, emptyList(), emptyList(), exchangeRates, today)
                val pluginMrr = plugin.series[OverviewDailyColumn.Mrr] ?: continue
                val pluginArr = plugin.series.getValue(OverviewDailyColumn.Arr)
                for ((pluginIndex, day) in plugin.days.withIndex()) {
                    val index = windows.index(day)
                    if (index in 0 until dayCount) {
                        mrr[index] += pluginMrr.values[pluginIndex] ?: BigDecimal.ZERO
                        arr[index] += pluginArr.values[pluginIndex] ?: BigDecimal.ZERO
                    }
                }
            }

            // the sums of the current day may still change
            val incompleteFrom = windows.incompleteSums
            // the recurring revenue of the current month is incomplete like in the overview,
            // it's the value of today, it changes until the end of the month
            val incompleteRecurringRevenue = windows.incompleteRecurringRevenue
            val currency = exchangeRates.targetCurrency.currencyCode
            val trialSeries = when {
                trials.isEmpty() -> emptyMap()
                else -> {
                    val trialsOfYear = windows.windowSums(trialCounts, windows.yearStarts)
                    mapOf(TRIALS to DailySeries("count", trialsOfYear.map(BigDecimal::valueOf), incompleteFrom))
                }
            }
            return AllPluginsDailyValues(
                days,
                trialSeries + mapOf(
                    SALES to DailySeries(currency, DailyWindows.amounts(totalsOfYear), incompleteFrom),
                    FEES to DailySeries(currency, DailyWindows.amounts(feesOfYear), incompleteFrom),
                    PAID to DailySeries(currency, DailyWindows.amounts(totalsOfYear.zip(feesOfYear) { total, fee -> total - fee }), incompleteFrom),
                    DOWNLOADS to DailySeries("count", downloadsOfYear.map(BigDecimal::valueOf), incompleteFrom),
                    MRR to DailySeries(currency, mrr.toList(), incompleteRecurringRevenue),
                    ARR to DailySeries(currency, arr.toList(), incompleteRecurringRevenue),
                    MONTH_SALES to DailySeries(currency, DailyWindows.amounts(totalsOfMonth), incompleteFrom),
                    MONTH_FEES to DailySeries(currency, DailyWindows.amounts(feesOfMonth), incompleteFrom),
                    MONTH_PAID to DailySeries(currency, DailyWindows.amounts(totalsOfMonth.zip(feesOfMonth) { total, fee -> total - fee }), incompleteFrom),
                    MONTH_DOWNLOADS to DailySeries("count", downloadsOfMonth.map(BigDecimal::valueOf), incompleteFrom),
                )
            )
        }
    }
}
