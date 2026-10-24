/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.overview

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
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
 * @param values The value of each day of [OverviewDailyValues.days].
 * @param incompleteFrom Index of the first day, whose value may still change.
 */
data class DailySeries(val unit: String, val values: List<BigDecimal>, val incompleteFrom: Int)

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

            return Builder(YearMonthDay.firstOfMonth(firstDate.year, firstDate.month), today, exchangeRates).run {
                OverviewDailyValues(
                    days,
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
    }

    private class Builder(start: YearMonthDay, private val today: YearMonthDay, private val exchangeRates: ExchangeRates) {
        private val first = start
        private val dayCount = (start daysUntil today).toInt() + 1
        val days: List<YearMonthDay> = List(dayCount) { start.add(0, 0, it) }

        private val currency = exchangeRates.targetCurrency.currencyCode
        private val zero: MonetaryAmount = FastMoney.zero(exchangeRates.targetCurrency)

        // the sums of the current day may still change
        private val incompleteSums = dayCount - 1

        // recurring revenue of the current month is incomplete like in the table,
        // because licenses renewed later in the month aren't included yet
        private val incompleteRecurringRevenue = maxOf(0, index(YearMonthDay.firstOfMonth(today.year, today.month)))

        /** Index of the first day of the month before each day, see [OverviewDailyValues]. */
        private val monthStartIndices = IntArray(dayCount) { index ->
            val day = days[index]
            val monthStart = when (day) {
                YearMonthDay.lastOfMonth(day.year, day.month) -> YearMonthDay.firstOfMonth(day.year, day.month)
                else -> day.add(0, -1, 1)
            }
            maxOf(0, index(monthStart))
        }

        private fun index(date: YearMonthDay): Int = (first daysUntil date).toInt()

        /** The amount in the displayed currency, like [dev.ja.marketplace.data.trackers.MonetaryAmountTracker]. */
        private fun convert(date: YearMonthDay, amountUSD: MonetaryAmount, amount: MonetaryAmount): MonetaryAmount {
            return when (exchangeRates.targetCurrency) {
                MarketplaceCurrencies.USD -> amountUSD
                amount.currency -> amount
                else -> exchangeRates.convert(date, amount)
            }
        }

        private fun amountSeries(values: List<MonetaryAmount>, incompleteFrom: Int): DailySeries {
            return DailySeries(
                currency,
                values.map { it.number.numberValue(BigDecimal::class.java).setScale(2, RoundingMode.HALF_UP) },
                incompleteFrom,
            )
        }

        /** @return The sales and the invoiced amounts, i.e. the sales without the fees, of the month before each day. */
        fun sales(sales: List<PluginSale>): Pair<DailySeries, DailySeries> {
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

            val totalsOfMonth = amountsOfMonth(totals)
            val feesOfMonth = amountsOfMonth(fees)
            return amountSeries(totalsOfMonth, incompleteSums) to
                    amountSeries(totalsOfMonth.mapIndexed { index, total -> total - feesOfMonth[index] }, incompleteSums)
        }

        private fun amountsOfMonth(amounts: Array<MonetaryAmount>): List<MonetaryAmount> {
            val sums = runningSums(amounts)
            return List(dayCount) { index ->
                val start = monthStartIndices[index]
                if (start == 0) sums[index] else sums[index] - sums[start - 1]
            }
        }

        private fun runningSums(amounts: Array<MonetaryAmount>): Array<MonetaryAmount> {
            val result = amounts.copyOf()
            for (index in 1 until dayCount) {
                result[index] = result[index - 1] + amounts[index]
            }
            return result
        }

        /** @return The sum of the values of the month before each day, e.g. downloads. */
        fun countsOfMonth(values: List<Pair<YearMonthDay, Long>>): DailySeries {
            val perDay = LongArray(dayCount)
            for ((date, value) in values) {
                val index = index(date)
                if (index in 0 until dayCount) {
                    perDay[index] += value
                }
            }
            for (index in 1 until dayCount) {
                perDay[index] += perDay[index - 1]
            }
            val sums = List(dayCount) { index ->
                val start = monthStartIndices[index]
                BigDecimal.valueOf(if (start == 0) perDay[index] else perDay[index] - perDay[start - 1])
            }
            return DailySeries("count", sums, incompleteSums)
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
            val from = maxOf(0, index(start))
            val to = minOf(dayCount - 1, index(end))
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
            val annualToMonthly = BigDecimal.ONE.divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP)
            val monthlyToAnnual = BigDecimal.valueOf(12)

            val salesByLicense = licenses
                .filter { it.validity != null && it.isPaidLicense && it.isSubscriptionLicense }
                .groupBy { it.id }
            for (licenseSales in salesByLicense.values) {
                // the latest sale only changes at the start or after the end of a sale
                val boundaries = licenseSales
                    .flatMap { listOf(it.validity!!.start, it.validity!!.end.add(0, 0, 1)) }
                    .distinct()
                    .sorted()
                for ((segmentStart, nextStart) in boundaries.zipWithNext()) {
                    // the first sale with the latest end, like RecurringRevenueTracker
                    val latest = licenseSales
                        .filter { segmentStart in it.validity!! }
                        .maxByOrNull { it.validity!!.end }
                        ?: continue

                    val mrrFactor = if (latest.sale.licensePeriod == LicensePeriod.Annual) annualToMonthly else BigDecimal.ONE
                    val arrFactor = if (latest.sale.licensePeriod == LicensePeriod.Monthly) monthlyToAnnual else BigDecimal.ONE
                    val mrr = convert(latest.sale.date, latest.amountUSD * mrrFactor, latest.amount * mrrFactor)
                    val arr = convert(latest.sale.date, latest.amountUSD * arrFactor, latest.amount * arrFactor)
                    addToDays(segmentStart, nextStart.add(0, 0, -1)) { from, to ->
                        mrrChanges[from] += mrr
                        mrrChanges[to + 1] -= mrr
                        arrChanges[from] += arr
                        arrChanges[to + 1] -= arr
                    }
                }
            }

            val mrr = runningSums(mrrChanges.copyOf(dayCount).requireNoNulls())
            val arr = runningSums(arrChanges.copyOf(dayCount).requireNoNulls())
            return amountSeries(mrr.toList(), incompleteRecurringRevenue) to amountSeries(arr.toList(), incompleteRecurringRevenue)
        }
    }
}
