/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.Currency
import dev.ja.marketplace.data.*
import java.util.*

class YearlySummaryTable : SimpleDataTable("Years", "years", "section-wide"), MarketplaceDataSink {
    private lateinit var downloads: List<MonthlyDownload>
    private val data = TreeMap<Int, YearSummary>()

    private data class YearSummary(val sales: PaymentAmountTracker)

    override fun init(data: PluginData) {
        this.downloads = data.downloadsMonthly
    }

    override fun process(sale: PluginSale) {
        val year = sale.date.year
        val yearData = data.computeIfAbsent(year) { YearSummary(PaymentAmountTracker(YearMonthDayRange.ofYear(year))) }
        yearData.sales.add(sale.date, sale.amountUSD)
    }

    override fun process(licenseInfo: LicenseInfo) {}

    private val columnYear = DataTableColumn("year", null)
    private val columnSalesTotal = DataTableColumn("sales", "Sales Total", "num")
    private val columnSalesFees = DataTableColumn("fees", "Fees", "num")
    private val columnSalesPaid = DataTableColumn("paid", "Paid", "num")
    private val columnDownloads = DataTableColumn("downloads", "Downloads", "num")

    override val columns: List<DataTableColumn> = listOf(
        columnYear,
        columnSalesTotal,
        columnSalesFees,
        columnSalesPaid,
        columnDownloads,
    )

    override val sections: List<DataTableSection>
        get() {
            val rows = data.entries.map { (year, value) ->
                SimpleDateTableRow(
                    columnYear to year,
                    columnSalesTotal to value.sales.totalAmountUSD.withCurrency(Currency.USD),
                    columnSalesFees to value.sales.feesAmountUSD.withCurrency(Currency.USD),
                    columnSalesPaid to value.sales.paidAmountUSD.withCurrency(Currency.USD),
                    columnDownloads to downloads
                        .filter { it.firstOfMonth.year == year }
                        .sumOf { it.downloads }
                        .toBigInteger(),
                )
            }

            return listOf(
                SimpleTableSection(
                    rows, footer = SimpleTableSection(
                        SimpleDateTableRow(
                            columnSalesTotal to data.values
                                .sumOf { it.sales.totalAmountUSD }
                                .withCurrency(Currency.USD),
                            columnSalesFees to data.values.sumOf { it.sales.feesAmountUSD }.withCurrency(Currency.USD),
                            columnSalesPaid to data.values.sumOf { it.sales.paidAmountUSD }.withCurrency(Currency.USD),
                            columnDownloads to downloads.sumOf { it.downloads.toBigInteger() },
                        )
                    )
                )
            )
        }
}