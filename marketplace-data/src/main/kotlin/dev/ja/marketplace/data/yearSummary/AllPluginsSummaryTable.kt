/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.MonthlyDownload
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.PaymentAmountTracker
import dev.ja.marketplace.exchangeRate.ExchangeRates
import java.math.BigInteger
import java.util.*

/**
 * The sales and downloads of all paid plugins of the account by year, like the first columns of [YearlySummaryTable].
 * The data of the plugins is added with [add], it doesn't use the data of a single plugin.
 */
class AllPluginsSummaryTable(private val displayExchangeRates: ExchangeRates) : SimpleDataTable("Years", "all-plugins", "table-column-wide") {
    private val columnYear = DataTableColumn("year", null)
    private val columnSalesTotal = DataTableColumn("sales", "Sales Total", "num", chartMeasure = ChartMeasure.Flow, dailyChartSeries = AllPluginsDailyValues.SALES)
    private val columnSalesFees = DataTableColumn("fees", "Fees", "num", chartMeasure = ChartMeasure.Flow, dailyChartSeries = AllPluginsDailyValues.FEES)
    private val columnSalesPaid = DataTableColumn("paid", "Paid", "num", chartMeasure = ChartMeasure.Flow, dailyChartSeries = AllPluginsDailyValues.PAID)
    private val columnDownloads = DataTableColumn("downloads", "Downloads", "num", chartMeasure = ChartMeasure.Flow, dailyChartSeries = AllPluginsDailyValues.DOWNLOADS)

    private val totalSales = PaymentAmountTracker(YearMonthDayRange.MAX, displayExchangeRates)
    private val salesByYear = TreeMap<Int, PaymentAmountTracker>(Comparator.reverseOrder())
    private val downloadsByYear = TreeMap<Int, BigInteger>(Comparator.reverseOrder())

    override val isChartable: Boolean = true
    override val chartPeriod: ChartPeriod = ChartPeriod.Year

    override val columns: List<DataTableColumn> = listOf(
        columnYear, columnSalesTotal, columnSalesFees, columnSalesPaid, columnDownloads,
    )

    /**
     * Adds the sales and the downloads of a plugin.
     */
    suspend fun add(sales: List<PluginSale>, downloads: List<MonthlyDownload>) {
        for (sale in sales) {
            totalSales.add(sale.date, sale.amountUSD, sale.amount)
            salesByYear.getOrPut(sale.date.year) { PaymentAmountTracker(YearMonthDayRange.ofYear(sale.date.year), displayExchangeRates) }
                .add(sale.date, sale.amountUSD, sale.amount)
        }
        for (download in downloads) {
            downloadsByYear.merge(download.firstOfMonth.year, download.downloads.toBigInteger(), BigInteger::add)
        }
    }

    override suspend fun init(data: PluginData) {
        throw UnsupportedOperationException("The data of all plugins is added with add()")
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
    }

    override suspend fun createSections(): List<DataTableSection> {
        val years = (salesByYear.keys + downloadsByYear.keys).toSortedSet(Comparator.reverseOrder())
        if (years.isEmpty()) {
            return emptyList()
        }

        val now = YearMonthDay.now()
        // the years without data between the first year and today are shown, too
        val rows = (now.year downTo years.last()).map { year ->
            val sales = salesByYear[year] ?: PaymentAmountTracker(YearMonthDayRange.ofYear(year), displayExchangeRates)
            SimpleDateTableRow(
                values = mapOf(
                    columnYear to year,
                    columnSalesTotal to sales.totalAmount,
                    columnSalesFees to sales.feesAmount,
                    columnSalesPaid to sales.paidAmount,
                    columnDownloads to (downloadsByYear[year] ?: BigInteger.ZERO),
                ),
                cssClass = if (year == now.year) "today" else null,
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
                            columnDownloads to downloadsByYear.values.fold(BigInteger.ZERO, BigInteger::add),
                        ),
                    )
                )
            )
        )
    }
}
