/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.PaymentAmountTracker
import dev.ja.marketplace.exchangeRate.ExchangeRates

/**
 * The sales of all paid plugins of the account for each day of the current month, with the total of the month.
 * The sales of the plugins are added with [add], it doesn't use the data of a single plugin.
 */
class AllPluginsMonthTable(
    private val displayExchangeRates: ExchangeRates,
    private val today: YearMonthDay = YearMonthDay.now(),
) : SimpleDataTable("This Month", "all-plugins-month", "table-column-medium") {
    private val columnDay = DataTableColumn("day", null)
    private val columnSalesTotal = DataTableColumn("sales", "Sales Total", "num", chartMeasure = ChartMeasure.Flow)
    private val columnSalesFees = DataTableColumn("fees", "Fees", "num", chartMeasure = ChartMeasure.Flow)
    private val columnSalesPaid = DataTableColumn("paid", "Invoice", "num", chartMeasure = ChartMeasure.Flow)

    private val month = YearMonthDayRange(YearMonthDay(today.year, today.month, 1), today)
    private val totalSales = PaymentAmountTracker(month, displayExchangeRates)
    private val days: List<YearMonthDay> = (0..(month.start daysUntil today).toInt()).map { month.start.add(0, 0, it) }
    private val salesByDay = days.associateWith { PaymentAmountTracker(YearMonthDayRange(it, it), displayExchangeRates) }

    override val isChartable: Boolean = true
    override val chartPeriod: ChartPeriod = ChartPeriod.Day

    // the last week, the other days of the month are shown on demand
    override val collapsedRowCount: Int = 7

    override val columns: List<DataTableColumn> = listOf(columnDay, columnSalesTotal, columnSalesFees, columnSalesPaid)

    /**
     * Adds the sales of a plugin, only the sales of the current month are used.
     */
    suspend fun add(sales: List<PluginSale>) {
        for (sale in sales) {
            if (sale.date in month) {
                totalSales.add(sale.date, sale.amountUSD, sale.amount)
                salesByDay[sale.date]?.add(sale.date, sale.amountUSD, sale.amount)
            }
        }
    }

    override suspend fun init(data: PluginData) {
        throw UnsupportedOperationException("The sales of all plugins are added with add()")
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
    }

    override suspend fun createSections(): List<DataTableSection> {
        val rows = days.reversed().map { day ->
            val sales = salesByDay.getValue(day)
            SimpleDateTableRow(
                values = mapOf(
                    columnDay to day,
                    columnSalesTotal to sales.totalAmount,
                    columnSalesFees to sales.feesAmount,
                    columnSalesPaid to sales.paidAmount,
                ),
                cssClass = if (day == today) "today" else null,
            )
        }

        return listOf(
            SimpleTableSection(
                rows = rows,
                footer = SimpleTableSection(
                    SimpleDateTableRow(
                        values = mapOf(
                            columnSalesTotal to totalSales.totalAmount,
                            columnSalesFees to totalSales.feesAmount,
                            columnSalesPaid to totalSales.paidAmount,
                        ),
                    )
                )
            )
        )
    }
}
