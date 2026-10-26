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
import dev.ja.marketplace.data.chart.ChartMetric
import dev.ja.marketplace.data.trackers.AnnualRecurringRevenueTracker
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import dev.ja.marketplace.data.trackers.MonthlyRecurringRevenueTracker
import dev.ja.marketplace.data.trackers.PaymentAmountTracker
import dev.ja.marketplace.data.trackers.RecurringRevenueSales
import dev.ja.marketplace.exchangeRate.ExchangeRates
import java.math.BigInteger
import java.util.*

/**
 * The sales, recurring revenue and downloads of all paid plugins of the account by year, like the columns of
 * [YearlySummaryTable]. The data of the plugins is added with [add], it doesn't use the data of a single plugin.
 *
 * MRR and ARR are the sums of the MRR and ARR of the plugins at the end of each year, with the rules of the overview.
 */
class AllPluginsSummaryTable(private val displayExchangeRates: ExchangeRates) : SimpleDataTable("Years", "all-plugins", "table-column-wide table-fixed-columns") {
    private val columnYear = DataTableColumn("year", null)
    private val columnSalesTotal = DataTableColumn("sales", "Sales Total", "num", chartMeasure = ChartMeasure.Flow, chartMetric = ChartMetric.Sales.id)
    private val columnSalesFees = DataTableColumn("fees", "Fees", "num", chartMeasure = ChartMeasure.Flow, chartMetric = ChartMetric.Fees.id)
    private val columnSalesPaid = DataTableColumn("paid", "Invoice", "num", chartMeasure = ChartMeasure.Flow, chartMetric = ChartMetric.Invoice.id)
    private val columnMrr = DataTableColumn(
        "mrr", "MRR", "num", tooltip = "Monthly recurring revenue at the end of the year",
        chartMeasure = ChartMeasure.Stock, chartMetric = ChartMetric.Mrr.id,
    )
    private val columnArr = DataTableColumn(
        "arr", "ARR", "num", tooltip = "Annual recurring revenue at the end of the year",
        chartMeasure = ChartMeasure.Stock, chartMetric = ChartMetric.Arr.id,
    )
    private val columnDownloads = DataTableColumn(
        "downloads", "↓", "num", chartTitle = "Downloads", tooltip = "Downloads",
        chartMeasure = ChartMeasure.Flow, chartMetric = ChartMetric.Downloads.id,
    )

    private val totalSales = PaymentAmountTracker(YearMonthDayRange.MAX, displayExchangeRates)
    private val salesByYear = TreeMap<Int, PaymentAmountTracker>(Comparator.reverseOrder())
    private val downloadsByYear = TreeMap<Int, BigInteger>(Comparator.reverseOrder())
    private val mrrByYear = HashMap<Int, MonetaryAmountTracker>()
    private val arrByYear = HashMap<Int, MonetaryAmountTracker>()

    override val isChartable: Boolean = true
    override val chartPeriod: ChartPeriod = ChartPeriod.Year

    // the last three years, the earlier years are shown on demand
    override val collapsedRowCount: Int = 3

    override val columns: List<DataTableColumn> = listOf(
        columnYear, columnSalesTotal, columnSalesFees, columnSalesPaid, columnMrr, columnArr, columnDownloads,
    )

    /**
     * Adds the sales, the licenses and the downloads of a plugin.
     */
    fun add(sales: List<PluginSale>, licenses: List<LicenseInfo>, downloads: List<MonthlyDownload>) {
        for (sale in sales) {
            totalSales.add(sale.date, sale.amountUSD, sale.amount)
            salesByYear.getOrPut(sale.date.year) { PaymentAmountTracker(YearMonthDayRange.ofYear(sale.date.year), displayExchangeRates) }
                .add(sale.date, sale.amountUSD, sale.amount)
        }
        for (download in downloads) {
            downloadsByYear.merge(download.firstOfMonth.year, download.downloads.toBigInteger(), BigInteger::add)
        }

        // the trackers of each plugin, the IDs of the licenses of different plugins could be the same
        val firstYear = licenses.mapNotNull { it.validity?.start?.year }.minOrNull() ?: return
        val subscriptions = licenses.filter(RecurringRevenueSales::isRecurring)
        val today = YearMonthDay.now()
        for (year in firstYear..today.year) {
            // the recurring revenue of the current year at today
            val yearRange = YearMonthDayRange.ofYear(year).let { it.copy(end = minOf(it.end, today)) }
            val mrr = MonthlyRecurringRevenueTracker(yearRange, displayExchangeRates)
            val arr = AnnualRecurringRevenueTracker(yearRange, displayExchangeRates)
            for (license in subscriptions) {
                mrr.processLicenseSale(license)
                arr.processLicenseSale(license)
            }
            mrrByYear.getOrPut(year) { MonetaryAmountTracker(displayExchangeRates) }.add(mrr.getResult().amounts)
            arrByYear.getOrPut(year) { MonetaryAmountTracker(displayExchangeRates) }.add(arr.getResult().amounts)
        }
    }

    override suspend fun init(data: PluginData) {
        throw UnsupportedOperationException("The data of all plugins is added with add()")
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
    }

    override suspend fun createSections(): List<DataTableSection> {
        val years = (salesByYear.keys + downloadsByYear.keys + mrrByYear.keys).toSortedSet(Comparator.reverseOrder())
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
                    // the recurring revenue of the current year is incomplete,
                    // it's the value of today, it changes until the end of the year
                    columnMrr to recurringRevenue(mrrByYear[year], year == now.year),
                    columnArr to recurringRevenue(arrByYear[year], year == now.year),
                    // the downloads of a day are reported on the next day at the earliest
                    columnDownloads to (downloadsByYear[year] ?: BigInteger.ZERO).let {
                        if (year == now.year) IncompleteValue(it) else it
                    },
                ),
                tooltips = mapOf(
                    columnMrr to IncompleteRecurringRevenueTooltip.takeIf { year == now.year },
                    columnArr to IncompleteRecurringRevenueTooltip.takeIf { year == now.year },
                    columnDownloads to IncompleteDownloadsTooltip.takeIf { year == now.year },
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

    private fun recurringRevenue(amounts: MonetaryAmountTracker?, isIncomplete: Boolean): Any {
        val amount = (amounts ?: MonetaryAmountTracker(displayExchangeRates)).getTotalAmount()
        return if (isIncomplete) IncompleteValue(amount) else amount
    }
}
