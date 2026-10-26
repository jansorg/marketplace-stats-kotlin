/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customerType

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.overview.DailySeries
import dev.ja.marketplace.exchangeRate.ExchangeRates
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The share of the sales of each customer type for each day, for the charts of [CustomerTypeTable] per day, week and year.
 *
 * The share of the month at a day is the share of the sales of the month before the day, i.e. from the day after the
 * same day of the previous month until the day. At the last day of a month, it's the share of the calendar month, like
 * the monthly values of the table. The share of the year is calculated the same way with the year before each day.
 * Days without sales in the month or year before them have no value.
 */
object CustomerTypeDailyValues {
    /** The ID of the column, see [dev.ja.marketplace.data.DataTableColumn.dailyChartSeries]. */
    const val SERIES_ID = "customer-type-share"

    /**
     * @return The ID of the values of a customer type, e.g. `customer-type-share.month.Individual`.
     *  The charts create it from the ID of the column and the name of the row.
     */
    fun seriesId(period: String, customerType: CustomerType): String = "$SERIES_ID.$period.$customerType"

    /**
     * @param days The days of the values, without gaps, e.g. the days of the overview.
     * @return The values of each customer type by [seriesId], for the periods `month` and `year`.
     */
    fun create(sales: List<PluginSale>, days: List<YearMonthDay>, exchangeRates: ExchangeRates): Map<String, DailySeries> {
        if (days.isEmpty()) {
            return emptyMap()
        }

        val first = days.first()
        val dayCount = days.size
        fun index(date: YearMonthDay): Int = (first daysUntil date).toInt()

        // the running sums of the amounts in the displayed currency, of all sales and of each customer type
        val totals = Array<BigDecimal>(dayCount) { BigDecimal.ZERO }
        val byType = CustomerType.entries.associateWith { Array<BigDecimal>(dayCount) { BigDecimal.ZERO } }
        for (sale in sales) {
            val index = index(sale.date)
            if (index in 0 until dayCount) {
                val amount = amount(sale, exchangeRates)
                totals[index] += amount
                byType.getValue(sale.customer.type)[index] += amount
            }
        }
        for (index in 1 until dayCount) {
            totals[index] += totals[index - 1]
            byType.values.forEach { it[index] += it[index - 1] }
        }

        // index of the first day of the month or year before each day, the last day of a period starts the calendar period
        val monthStarts = IntArray(dayCount) { index ->
            val day = days[index]
            val start = when (day) {
                YearMonthDay.lastOfMonth(day.year, day.month) -> YearMonthDay.firstOfMonth(day.year, day.month)
                else -> day.add(0, -1, 1)
            }
            index(start)
        }
        val yearStarts = IntArray(dayCount) { index ->
            val day = days[index]
            val start = when {
                day.month == 12 && day.day == 31 -> YearMonthDay(day.year, 1, 1)
                else -> day.add(-1, 0, 1)
            }
            index(start)
        }

        fun sum(sums: Array<BigDecimal>, start: Int, index: Int): BigDecimal {
            return if (start <= 0) sums[index] else sums[index] - sums[start - 1]
        }

        fun shares(type: CustomerType, starts: IntArray): List<BigDecimal?> {
            val sums = byType.getValue(type)
            return List(dayCount) { index ->
                val total = sum(totals, starts[index], index)
                when (total.signum()) {
                    0 -> null
                    else -> sum(sums, starts[index], index).multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP)
                }
            }
        }

        // the sums of today may still change
        val incompleteFrom = dayCount - 1
        return CustomerType.entries.flatMap { type ->
            listOf(
                seriesId("month", type) to DailySeries("%", shares(type, monthStarts), incompleteFrom),
                seriesId("year", type) to DailySeries("%", shares(type, yearStarts), incompleteFrom),
            )
        }.toMap()
    }

    private val HUNDRED = BigDecimal(100)

    private fun amount(sale: PluginSale, exchangeRates: ExchangeRates): BigDecimal {
        val amount = when (exchangeRates.targetCurrency) {
            MarketplaceCurrencies.USD -> sale.amountUSD
            sale.amount.currency -> sale.amount
            else -> exchangeRates.convert(sale.date, sale.amount)
        }
        return amount.number.numberValue(BigDecimal::class.java)
    }
}
