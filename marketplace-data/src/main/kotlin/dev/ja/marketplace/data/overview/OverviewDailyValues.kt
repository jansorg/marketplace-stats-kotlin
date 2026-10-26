/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.overview

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.data.trackers.RecurringRevenueSales
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.javamoney.moneta.FastMoney
import java.math.BigDecimal
import java.time.DayOfWeek
import javax.money.MonetaryAmount

/**
 * The values of a column of the overview table for each day.
 *
 * @param unit The currency code for amounts and `count` for numbers, like [dev.ja.marketplace.data.ChartValue.unit].
 * @param values The value of each day of [OverviewDailyValues.days], `null` if there is no value, e.g. no average without customers.
 * @param incompleteFrom Index of the first day, whose value may still change.
 */
data class DailySeries(val unit: String, val values: List<BigDecimal?>, val incompleteFrom: Int)

/**
 * The columns of the overview table, which have values for each day. The [id] is used by the charts.
 */
enum class OverviewDailyColumn(val id: String) {
    Sales("sales"),
    Invoice("invoice"),
    Licenses("licenses"),
    PaidLicenses("licenses-paid"),
    Mrr("mrr"),
    Arr("arr"),
    Downloads("downloads"),
    Trials("trials"),
}

/**
 * The values of the overview table for each day, for charts with a finer resolution than the months of the table.
 *
 * The values have the meaning and the scale of the values of the table:
 * - Sums, e.g. of sales, downloads and trials, are the sums of the month before each day,
 *   i.e. from the day after the same day of the previous month until the day.
 *   At the last day of a month, it's the sum of the calendar month like in the table.
 * - Licenses, MRR and ARR are the values at each day, like the values at the end of each month in the table.
 *   They use the rules of [dev.ja.marketplace.data.trackers.LicenseTracker] and
 *   [dev.ja.marketplace.data.trackers.RecurringRevenueTracker].
 *
 * @param days The days from the first month with data until today.
 */
class OverviewDailyValues private constructor(
    val days: List<YearMonthDay>,
    val series: Map<OverviewDailyColumn, DailySeries>,
) {
    /**
     * @return The indices of the last day of each week, i.e. of each Sunday, and of today.
     */
    fun weekEndIndices(): List<Int> {
        return days.indices.filter { index ->
            index == days.lastIndex || days[index].toLocalDate().dayOfWeek == DayOfWeek.SUNDAY
        }
    }

    companion object {
        fun create(
            sales: List<PluginSale>,
            licenses: List<LicenseInfo>,
            trials: List<PluginTrial>,
            downloads: List<DailyDownload>,
            exchangeRates: ExchangeRates,
            today: YearMonthDay = YearMonthDay.now(),
        ): OverviewDailyValues {
            val firstDate = listOfNotNull(
                sales.minOfOrNull { it.date },
                licenses.mapNotNull { it.validity?.start }.minOrNull(),
                trials.minOfOrNull { it.date },
                downloads.filter { it.downloads > 0 }.minOfOrNull { it.day },
            ).minOrNull()
            if (firstDate == null || firstDate > today) {
                return OverviewDailyValues(emptyList(), emptyMap())
            }

            val windows = DailyWindows(YearMonthDay.firstOfMonth(firstDate.year, firstDate.month), today)
            return Builder(windows, exchangeRates).run {
                OverviewDailyValues(
                    windows.days,
                    buildMap {
                        val (salesSeries, invoiceSeries) = sales(sales)
                        put(OverviewDailyColumn.Sales, salesSeries)
                        put(OverviewDailyColumn.Invoice, invoiceSeries)
                        put(OverviewDailyColumn.Licenses, licenseCount(licenses.filter { it.validity != null }))
                        put(OverviewDailyColumn.PaidLicenses, licenseCount(licenses.filter { it.validity != null && it.isPaidLicense }))
                        val (mrr, arr) = recurringRevenue(licenses)
                        put(OverviewDailyColumn.Mrr, mrr)
                        put(OverviewDailyColumn.Arr, arr)
                        put(OverviewDailyColumn.Downloads, countsOfMonth(downloads.map { it.day to it.downloads }))
                        put(OverviewDailyColumn.Trials, countsOfMonth(trials.map { it.date to 1L }))
                    }
                )
            }
        }

        /** @return The MRR and the ARR at each day of [windows], like [OverviewDailyColumn.Mrr] and [OverviewDailyColumn.Arr]. */
        fun recurringRevenue(licenses: List<LicenseInfo>, windows: DailyWindows, exchangeRates: ExchangeRates): Pair<DailySeries, DailySeries> {
            return Builder(windows, exchangeRates).recurringRevenue(licenses)
        }
    }

    private class Builder(private val windows: DailyWindows, private val exchangeRates: ExchangeRates) {
        private val dayCount = windows.dayCount
        private val currency = exchangeRates.targetCurrency.currencyCode
        private val zero: MonetaryAmount = FastMoney.zero(exchangeRates.targetCurrency)

        // the recurring revenue of the current month is incomplete like in the table,
        // it's the value of today, it changes until the end of the month
        private val incompleteRecurringRevenue = windows.incompleteRecurringRevenue

        private fun amountSeries(values: List<MonetaryAmount>, incompleteFrom: Int): DailySeries {
            return DailySeries(currency, DailyWindows.amounts(values), incompleteFrom)
        }

        /** @return The sales and the invoiced amounts, i.e. the sales without the fees, of the month before each day. */
        fun sales(sales: List<PluginSale>): Pair<DailySeries, DailySeries> {
            val (totals, fees) = windows.salesAndFeesPerDay(sales, exchangeRates)
            val totalsOfMonth = windows.windowSums(totals, windows.monthStarts)
            val feesOfMonth = windows.windowSums(fees, windows.monthStarts)
            return amountSeries(totalsOfMonth, windows.incompleteSums) to
                    amountSeries(totalsOfMonth.mapIndexed { index, total -> total - feesOfMonth[index] }, windows.incompleteSums)
        }

        /** @return The sum of the values of the month before each day, e.g. downloads. */
        fun countsOfMonth(values: List<Pair<YearMonthDay, Long>>): DailySeries {
            val sums = windows.windowSums(windows.countsPerDay(values), windows.monthStarts)
            return DailySeries("count", sums.map(BigDecimal::valueOf), windows.incompleteSums)
        }

        /** @return The number of licenses, which are valid at each day. Licenses with several sales are counted once. */
        fun licenseCount(licenses: List<LicenseInfo>): DailySeries {
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
            return DailySeries("count", counts, dayCount)
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
         * @return The MRR and the ARR at each day. Only paid subscription licenses contribute and only the latest
         * sale of each license, which is valid at the day.
         */
        fun recurringRevenue(licenses: List<LicenseInfo>): Pair<DailySeries, DailySeries> {
            val mrrChanges = Array(dayCount + 1) { zero }
            val arrChanges = Array(dayCount + 1) { zero }
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

            val mrr = windows.runningSums(mrrChanges.copyOf(dayCount).requireNoNulls())
            val arr = windows.runningSums(arrChanges.copyOf(dayCount).requireNoNulls())
            return amountSeries(mrr.toList(), incompleteRecurringRevenue) to amountSeries(arr.toList(), incompleteRecurringRevenue)
        }
    }
}
