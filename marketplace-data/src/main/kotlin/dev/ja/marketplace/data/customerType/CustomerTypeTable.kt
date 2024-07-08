/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customerType

import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.*
import java.util.*

class CustomerTypeTable : SimpleDataTable("Customer Type", "customer-type"), MarketplaceDataSink {
    private val columnType = DataTableColumn("customer-type", null)
    private val columnAmount = DataTableColumn("amount", null, "num")
    private val columnPercentage = DataTableColumn("percentage", "% of Sales", "num num-percentage")
    override val columns: List<DataTableColumn> = listOf(columnType, columnAmount, columnPercentage)

    private val data = TreeMap<CustomerType, Amount>()

    override fun process(sale: PluginSale) {
        data.merge(sale.customer.type, sale.amountUSD, Amount::plus)
    }

    override fun process(licenseInfo: LicenseInfo) {
        // ignored
    }

    override fun createSections(): List<DataTableSection> {
        val totalAmount = data.values.sumOf { it }
        val rows = data.entries.map { (customerType, amount) ->
            SimpleDateTableRow(
                columnType to customerType,
                columnAmount to amount.withCurrency(MarketplaceCurrencies.USD),
                columnPercentage to PercentageValue.of(amount, totalAmount)
            )
        }

        return listOf(
            SimpleTableSection(
                rows,
                footer = SimpleTableSection(
                    SimpleDateTableRow(
                        columnAmount to totalAmount.withCurrency(MarketplaceCurrencies.USD),
                        columnPercentage to PercentageValue.ONE_HUNDRED,
                    )
                )
            )
        )
    }
}