/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customerType

import dev.ja.marketplace.data.*
import dev.ja.marketplace.client.Amount
import dev.ja.marketplace.client.Currency
import dev.ja.marketplace.client.CustomerType
import dev.ja.marketplace.client.withCurrency
import java.math.BigDecimal
import java.util.*

class CustomerTypeTable : SimpleDataTable("Customer Type", "customer-type"), MarketplaceDataSink {
    private val columnType = DataTableColumn("customer-type", null, "num")
    private val columnAmount = DataTableColumn("amount", null, "num")
    private val columnPercentage = DataTableColumn("percentage", "% of Sales", "num num-percentage")

    private val data = TreeMap<CustomerType, Amount>()

    override val columns: List<DataTableColumn> = listOf(columnType, columnAmount, columnPercentage)

    override val sections: List<DataTableSection>
        get() {
            val totalAmount = data.values.sumOf { it }
            val rows = data.entries.map { (customerType, amount) ->
                SimpleDateTableRow(
                    mapOf(
                        columnType to customerType,
                        columnAmount to amount.withCurrency(Currency.USD),
                        columnPercentage to PercentageValue.of(amount, totalAmount)
                    )
                )
            }

            return listOf(
                SimpleTableSection(
                    rows, footer = SimpleTableSection(
                        SimpleDateTableRow(
                            columnPercentage to PercentageValue.ONE_HUNDRED,
                            columnAmount to data.values.sumOf { it }.withCurrency(Currency.USD)
                        )
                    )
                )
            )
        }

    override fun process(licenseInfo: LicenseInfo) {
        data.compute(licenseInfo.sale.customer.type) { _, current ->
            (current ?: BigDecimal.ZERO) + licenseInfo.amountUSD
        }
    }
}