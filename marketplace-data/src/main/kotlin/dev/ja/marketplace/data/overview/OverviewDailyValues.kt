/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.overview

import dev.ja.marketplace.churn.ChurnValidities
import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.data.PercentageValue
import dev.ja.marketplace.data.trackers.RecurringRevenueSales
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.javamoney.moneta.FastMoney
import java.math.BigDecimal
import java.math.RoundingMode
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
    ChurnAnnual("churn-annual-paid"),
    ChurnMonthly("churn-monthly-paid"),
    ChurnAnnualOfYear("churn-annual-paid.year"),
    ChurnMonthlyOfYear("churn-monthly-paid.year"),
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
 * - The churn is the churn of the month before each day, i.e. of the paid licenses, which were active a month before
 *   the day and aren't active at the day. At the last day of a month, it's the churn of the calendar month like in the
 *   table. The series `*.year` are the churn of the year before each day, at the end of a year the churn of the year.
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
                        // the churn of the current month and year is incomplete like in the table
                        val validities = ChurnValidities(licenses)
                        val currentMonth = YearMonthDay.firstOfMonth(today.year, today.month)
                        val currentYear = YearMonthDay(today.year, 1, 1)
                        put(OverviewDailyColumn.ChurnAnnual, churn(licenses, validities, LicensePeriod.Annual, ::monthBefore, currentMonth))
                        put(OverviewDailyColumn.ChurnMonthly, churn(licenses, validities, LicensePeriod.Monthly, ::monthBefore, currentMonth))
                        put(OverviewDailyColumn.ChurnAnnualOfYear, churn(licenses, validities, LicensePeriod.Annual, ::yearBefore, currentYear))
                        put(OverviewDailyColumn.ChurnMonthlyOfYear, churn(licenses, validities, LicensePeriod.Monthly, ::yearBefore, currentYear))
                    }
                )
            }
        }

        /** @return The day before the month before [day], the last day of the previous month at the end of a month. */
        private fun monthBefore(day: YearMonthDay): YearMonthDay = when (day) {
            YearMonthDay.lastOfMonth(day.year, day.month) -> YearMonthDay.firstOfMonth(day.year, day.month).add(0, 0, -1)
            else -> day.add(0, -1, 0)
        }

        /** @return The day before the year before [day], the last day of the previous year at the end of a year. */
        private fun yearBefore(day: YearMonthDay): YearMonthDay = when {
            day.month == 12 && day.day == 31 -> YearMonthDay(day.year - 1, 12, 31)
            else -> day.add(-1, 0, 0)
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
         * @return The churn rate in percent at each day, like [dev.ja.marketplace.churn.LicenseChurnProcessor]: the
         * share of the paid licenses of the [period], which were active at the [previous] day, and which aren't active
         * at the day, e.g. not renewed within the grace period. `null` without licenses at the previous day.
         * The rates from [incompleteFrom] are incomplete, licenses expiring later may still be renewed.
         */
        fun churn(
            licenses: List<LicenseInfo>,
            validities: ChurnValidities,
            period: LicensePeriod,
            previous: (YearMonthDay) -> YearMonthDay,
            incompleteFrom: YearMonthDay,
        ): DailySeries {
            val previousDays = windows.days.map(previous)
            val previousChanges = IntArray(dayCount + 1)
            val churnedChanges = IntArray(dayCount + 1)
            for (sales in licenses.groupBy { it.id }.values) {
                val accepted = mergeRanges(sales.filter { it.isPaidLicense && it.sale.licensePeriod == period }.mapNotNull(validities::validity))
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
            val rates = List(dayCount) { index ->
                previousCount += previousChanges[index]
                churnedCount += churnedChanges[index]
                PercentageValue.rateOf(churnedCount, previousCount)?.value?.setScale(2, RoundingMode.HALF_UP)
            }
            return DailySeries("%", rates, maxOf(0, windows.index(incompleteFrom)))
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
