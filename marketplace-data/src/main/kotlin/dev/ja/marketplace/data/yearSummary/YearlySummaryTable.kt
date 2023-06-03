/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.Currency
import dev.ja.marketplace.client.PluginSale
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.withCurrency
import dev.ja.marketplace.data.*
import java.util.*

class YearlySummaryTable : SimpleDataTable("Years", "years", "section-wide"), MarketplaceDataSink {
    private val data = TreeMap<Int, YearSummary>()

    private data class YearSummary(
        val sales: PaymentAmountTracker,
        var downloads: Int = 0,
    )

    override fun process(sale: PluginSale) {
        val yearData = data.computeIfAbsent(sale.date.year) {
            YearSummary(PaymentAmountTracker(YearMonthDayRange.ofYear(sale.date.year)))
        }
        yearData.sales.add(sale.date, sale.amountUSD)
    }

    override fun process(licenseInfo: LicenseInfo) {}

    private val columnYear = DataTableColumn("year", null)
    private val columnSalesTotal = DataTableColumn("sales", "Sales Total", "num")
    private val columnSalesFees = DataTableColumn("fees", "Fees", "num")
    private val columnSalesPaid = DataTableColumn("paid", "Paid", "num")

    override val columns: List<DataTableColumn> = listOf(
        columnYear,
        columnSalesTotal,
        columnSalesFees,
        columnSalesPaid,
    )

    override val sections: List<DataTableSection>
        get() {
            val rows = data.entries.map { (year, value) ->
                SimpleDateTableRow(
                    columnYear to year,
                    columnSalesTotal to value.sales.totalAmountUSD.withCurrency(Currency.USD),
                    columnSalesFees to value.sales.feesAmountUSD.withCurrency(Currency.USD),
                    columnSalesPaid to value.sales.paidAmountUSD.withCurrency(Currency.USD)
                )
            }

            return listOf(
                SimpleTableSection(
                    rows, footer = SimpleTableSection(
                        SimpleDateTableRow(
                            columnSalesTotal to data.values.sumOf { it.sales.totalAmountUSD }
                                .withCurrency(Currency.USD),
                            columnSalesFees to data.values.sumOf { it.sales.feesAmountUSD }.withCurrency(Currency.USD),
                            columnSalesPaid to data.values.sumOf { it.sales.paidAmountUSD }.withCurrency(Currency.USD),
                        )
                    )
                )
            )
        }
}