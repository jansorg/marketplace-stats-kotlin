/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.salesToday

import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.*

class SalesTodayTable : SimpleDataTable("Sales Today", cssClass = "small table-striped"), MarketplaceDataSink {
    private val columnSubscriptionType = DataTableColumn("subscription", null, "col-right")
    private val columnCustomerType = DataTableColumn("type", null, "col-right")
    private val columnAmount = DataTableColumn("amount", "Sales", "num")
    override val columns: List<DataTableColumn> = listOf(columnSubscriptionType, columnCustomerType, columnAmount)

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

    override fun createSections(): List<DataTableSection> {
        val salesTable = sales
            .groupBy { it.customer.type }
            .mapValues { it.value.groupBy { sale -> sale.licensePeriod } }
            .flatMap { (type, licensePeriodWithSales) ->
                licensePeriodWithSales.map { (licensePeriod, sales) ->
                    SimpleDateTableRow(
                        columnCustomerType to type,
                        columnSubscriptionType to licensePeriod,
                        columnAmount to sales.sumOf { it.amountUSD }.withCurrency(Currency.USD),
                    )
                }
            }.sortedByDescending { it.values[columnAmount] as? AmountWithCurrency }

        return listOf(
            SimpleTableSection(
                rows = salesTable,
                footer = SimpleRowGroup(
                    SimpleDateTableRow(
                        columnAmount to sales.sumOf { it.amountUSD }.withCurrency(Currency.USD)
                    )
                )
            )
        )
    }
}