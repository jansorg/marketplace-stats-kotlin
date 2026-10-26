/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customerType

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.overview.DailySeries
import dev.ja.marketplace.data.overview.DailyWindows
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

        val windows = DailyWindows(days.first(), days.last())

        // the amounts in the displayed currency of each day, of all sales and of each customer type
        val totals = Array<BigDecimal>(windows.dayCount) { BigDecimal.ZERO }
        val byType = CustomerType.entries.associateWith { Array<BigDecimal>(windows.dayCount) { BigDecimal.ZERO } }
        for (sale in sales) {
            val index = windows.index(sale.date)
            if (index in 0 until windows.dayCount) {
                val amount = exchangeRates.targetAmount(sale.date, sale.amountUSD, sale.amount).number.numberValue(BigDecimal::class.java)
                totals[index] += amount
                byType.getValue(sale.customer.type)[index] += amount
            }
        }

        fun shares(type: CustomerType, starts: IntArray): List<BigDecimal?> {
            val windowTotals = windows.windowSums(totals, starts)
            val windowSums = windows.windowSums(byType.getValue(type), starts)
            return windowTotals.mapIndexed { index, total ->
                when (total.signum()) {
                    0 -> null
                    else -> windowSums[index].multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP)
                }
            }
        }

        return CustomerType.entries.flatMap { type ->
            listOf(
                seriesId("month", type) to DailySeries("%", shares(type, windows.monthStarts), windows.incompleteSums),
                seriesId("year", type) to DailySeries("%", shares(type, windows.yearStarts), windows.incompleteSums),
            )
        }.toMap()
    }

    private val HUNDRED = BigDecimal(100)
}
