/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.salesToday

import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.*

class SalesTodayTable : SimpleDataTable("Sales Today", cssClass = "small table-striped"), MarketplaceDataSink {
    private val columnType = DataTableColumn("type", null, "num")
    private val columnCountry = DataTableColumn("country", null, "num")
    private val columnAmount = DataTableColumn("amount", null, "num")
    override val columns: List<DataTableColumn> = listOf(columnType, columnCountry, columnAmount)

    private val now = YearMonthDay.now()
    private val sales = mutableListOf<PluginSale>()

    override fun process(sale: PluginSale) {
        if (sale.date == now) {
            sales += sale
        }
    }

    override fun process(licenseInfo: LicenseInfo) {
        // ignored
    }

    override val sections: List<DataTableSection>
        get() {
            val salesTable = sales
                .groupBy { it.customer.type }
                .mapValues { it.value.groupBy { it.customer.country } }
                .flatMap { (type, countrySales) ->
                    countrySales.map { (country, sales) ->
                        SimpleDateTableRow(
                            columnType to type,
                            columnCountry to country,
                            columnAmount to sales.sumOf { it.amountUSD }.withCurrency(Currency.USD),
                        )
                    }
                }.sortedByDescending { it.values[columnAmount] as? AmountWithCurrency }

            return listOf(SimpleTableSection(salesTable))
        }
}