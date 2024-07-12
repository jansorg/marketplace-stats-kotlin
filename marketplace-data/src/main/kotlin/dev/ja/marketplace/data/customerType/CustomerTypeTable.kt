/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customerType

import dev.ja.marketplace.client.CustomerType
import dev.ja.marketplace.client.PluginSale
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import java.util.*

class CustomerTypeTable : SimpleDataTable("Customer Type", "customer-type"), MarketplaceDataSink {
    private val columnType = DataTableColumn("customer-type", null)
    private val columnAmount = DataTableColumn("amount", null, "num")
    private val columnPercentage = DataTableColumn("percentage", "% of Sales", "num num-percentage")

    private lateinit var totalAmount: MonetaryAmountTracker
    private val customerTypes = TreeMap<CustomerType, MonetaryAmountTracker>()

    override val columns: List<DataTableColumn> = listOf(columnType, columnAmount, columnPercentage)

    override suspend fun init(data: PluginData) {
        super.init(data)

        totalAmount = MonetaryAmountTracker(data.exchangeRates)
    }

    override suspend fun process(sale: PluginSale) {
        totalAmount.add(sale.date, sale.amountUSD, sale.amount)

        val tracker = customerTypes.computeIfAbsent(sale.customer.type) { MonetaryAmountTracker(exchangeRates) }
        tracker.add(sale.date, sale.amountUSD, sale.amount)
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        // ignored
    }

    override suspend fun createSections(): List<DataTableSection> {
        val totalAmount = this.totalAmount.getTotalAmount()

        val rows = customerTypes.entries.map { (customerType, amount) ->
            SimpleDateTableRow(
                columnType to customerType,
                columnAmount to amount.getTotalAmount(),
                columnPercentage to PercentageValue.of(amount.getTotalAmount(), totalAmount)
            )
        }

        return listOf(
            SimpleTableSection(
                rows,
                footer = SimpleTableSection(
                    SimpleDateTableRow(
                        columnAmount to totalAmount,
                        columnPercentage to PercentageValue.ONE_HUNDRED,
                    )
                )
            )
        )
    }
}