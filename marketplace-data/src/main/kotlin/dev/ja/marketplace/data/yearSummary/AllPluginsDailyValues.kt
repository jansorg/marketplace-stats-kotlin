/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.minus
import dev.ja.marketplace.client.plus
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.overview.DailySeries
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.javamoney.moneta.FastMoney
import java.math.BigDecimal
import java.math.RoundingMode
import javax.money.MonetaryAmount

/**
 * The values of [AllPluginsSummaryTable] for each day, for charts with a finer resolution than the years of the table.
 *
 * The values are the sums of the year before each day, i.e. from the day after the same day of the previous year until
 * the day. At the last day of a year, it's the sum of the calendar year like in the table.
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

        /**
         * @param sales The sales of all paid plugins.
         * @param downloads The downloads of all paid plugins.
         */
        fun create(
            sales: List<PluginSale>,
            downloads: List<DailyDownload>,
            exchangeRates: ExchangeRates,
            today: YearMonthDay = YearMonthDay.now(),
        ): AllPluginsDailyValues {
            val firstDate = listOfNotNull(
                sales.minOfOrNull { it.date },
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

            for (index in 1 until dayCount) {
                totals[index] += totals[index - 1]
                fees[index] += fees[index - 1]
                downloadCounts[index] += downloadCounts[index - 1]
            }
            fun <T> sumOfYear(index: Int, sums: (Int) -> T, minus: (T, T) -> T, zero: T): T {
                val start = yearStartIndices[index]
                return if (start == 0) sums(index) else minus(sums(index), sums(start - 1))
            }

            val totalsOfYear = List(dayCount) { sumOfYear(it, totals::get, { a, b -> a - b }, zero) }
            val feesOfYear = List(dayCount) { sumOfYear(it, fees::get, { a, b -> a - b }, zero) }
            val downloadsOfYear = List(dayCount) { sumOfYear(it, downloadCounts::get, Long::minus, 0L) }

            fun amounts(values: List<MonetaryAmount>): List<BigDecimal?> {
                return values.map { it.number.numberValue(BigDecimal::class.java).setScale(2, RoundingMode.HALF_UP) }
            }

            // the sums of the current day may still change
            val incompleteFrom = dayCount - 1
            val currency = exchangeRates.targetCurrency.currencyCode
            return AllPluginsDailyValues(
                days,
                mapOf(
                    SALES to DailySeries(currency, amounts(totalsOfYear), incompleteFrom),
                    FEES to DailySeries(currency, amounts(feesOfYear), incompleteFrom),
                    PAID to DailySeries(currency, amounts(totalsOfYear.zip(feesOfYear) { total, fee -> total - fee }), incompleteFrom),
                    DOWNLOADS to DailySeries("count", downloadsOfYear.map(BigDecimal::valueOf), incompleteFrom),
                )
            )
        }
    }
}
