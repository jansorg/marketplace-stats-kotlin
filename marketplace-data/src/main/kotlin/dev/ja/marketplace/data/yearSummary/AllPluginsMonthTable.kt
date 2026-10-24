/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.AnnualRecurringRevenueTracker
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import dev.ja.marketplace.data.trackers.MonthlyRecurringRevenueTracker
import dev.ja.marketplace.data.trackers.PaymentAmountTracker
import dev.ja.marketplace.exchangeRate.ExchangeRates

/**
 * The sales, recurring revenue and downloads of all paid plugins of the account for each day of the current month,
 * with the totals of the month. The data of the plugins is added with [add], it doesn't use the data of a single plugin.
 *
 * MRR and ARR are the sums of the MRR and ARR of the plugins at each day, with the rules of the overview.
 */
class AllPluginsMonthTable(
    private val displayExchangeRates: ExchangeRates,
    private val today: YearMonthDay = YearMonthDay.now(),
) : SimpleDataTable("This Month", "all-plugins-month", "table-column-medium") {
    private val columnDay = DataTableColumn("day", null)
    private val columnSalesTotal = DataTableColumn("sales", "Sales Total", "num", chartMeasure = ChartMeasure.Flow)
    private val columnSalesFees = DataTableColumn("fees", "Fees", "num", chartMeasure = ChartMeasure.Flow)
    private val columnSalesPaid = DataTableColumn("paid", "Invoice", "num", chartMeasure = ChartMeasure.Flow)
    private val columnMrr = DataTableColumn(
        "mrr", "MRR", "num", tooltip = "Monthly recurring revenue at the day", chartMeasure = ChartMeasure.Stock,
    )
    private val columnArr = DataTableColumn(
        "arr", "ARR", "num", tooltip = "Annual recurring revenue at the day", chartMeasure = ChartMeasure.Stock,
    )
    private val columnDownloads = DataTableColumn(
        "downloads", "↓", "num", chartTitle = "Downloads", tooltip = "Downloads", chartMeasure = ChartMeasure.Flow,
    )

    private val month = YearMonthDayRange(YearMonthDay(today.year, today.month, 1), today)
    private val totalSales = PaymentAmountTracker(month, displayExchangeRates)
    private val days: List<YearMonthDay> = (0..(month.start daysUntil today).toInt()).map { month.start.add(0, 0, it) }
    private val salesByDay = days.associateWith { PaymentAmountTracker(YearMonthDayRange(it, it), displayExchangeRates) }
    private val mrrByDay = days.associateWith { MonetaryAmountTracker(displayExchangeRates) }
    private val arrByDay = days.associateWith { MonetaryAmountTracker(displayExchangeRates) }
    private val downloadsByDay = days.associateWith { 0L }.toMutableMap()

    override val isChartable: Boolean = true
    override val chartPeriod: ChartPeriod = ChartPeriod.Day

    // the last week, the other days of the month are shown on demand
    override val collapsedRowCount: Int = 7

    override val columns: List<DataTableColumn> = listOf(
        columnDay, columnSalesTotal, columnSalesFees, columnSalesPaid, columnMrr, columnArr, columnDownloads,
    )

    /**
     * Adds the sales, the licenses and the downloads of a plugin, only the data of the current month is used.
     */
    suspend fun add(sales: List<PluginSale>, licenses: List<LicenseInfo>, downloads: List<DailyDownload>) {
        for (sale in sales) {
            if (sale.date in month) {
                totalSales.add(sale.date, sale.amountUSD, sale.amount)
                salesByDay[sale.date]?.add(sale.date, sale.amountUSD, sale.amount)
            }
        }

        for (download in downloads) {
            downloadsByDay.computeIfPresent(download.day) { _, count -> count + download.downloads }
        }

        // the trackers of each plugin, the IDs of the licenses of different plugins could be the same
        val subscriptions = licenses.filter { it.isPaidLicense && it.isSubscriptionLicense && it.validity != null }
        for (day in days) {
            val mrr = MonthlyRecurringRevenueTracker(YearMonthDayRange(day, day), displayExchangeRates)
            val arr = AnnualRecurringRevenueTracker(YearMonthDayRange(day, day), displayExchangeRates)
            for (license in subscriptions) {
                mrr.processLicenseSale(license)
                arr.processLicenseSale(license)
            }
            mrrByDay.getValue(day).add(mrr.getResult().amounts)
            arrByDay.getValue(day).add(arr.getResult().amounts)
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
                    columnMrr to mrrByDay.getValue(day).getTotalAmount(),
                    columnArr to arrByDay.getValue(day).getTotalAmount(),
                    columnDownloads to downloadsByDay.getValue(day).toBigInteger(),
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
                            columnDownloads to downloadsByDay.values.sum().toBigInteger(),
                        ),
                    )
                )
            )
        )
    }
}
