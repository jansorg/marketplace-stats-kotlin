/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.topCountries

import dev.ja.marketplace.client.Amount
import dev.ja.marketplace.client.Country
import dev.ja.marketplace.client.Currency
import dev.ja.marketplace.client.withCurrency
import dev.ja.marketplace.data.*
import java.math.BigDecimal
import java.util.*

class TopCountriesTable(private val maxItems: Int = 10) :
    SimpleDataTable(if (maxItems != Int.MAX_VALUE) "Top Countries" else "Countries", "top-countries"),
    MarketplaceDataSink {

    private val columnCountry = DataTableColumn("country", null, "col-right")
    private val columnSales = DataTableColumn("sales", null, "num")
    private val columnSalesPercentage = DataTableColumn("sales", "% of Sales", "num num-percentage")

    private val data = TreeMap<Country, Amount>()

    override val columns: List<DataTableColumn> = listOf(columnCountry, columnSales, columnSalesPercentage)

    override fun process(licenseInfo: LicenseInfo) {
        data.compute(licenseInfo.sale.customer.country) { _, amount ->
            (amount ?: BigDecimal.ZERO) + licenseInfo.amountUSD
        }
    }

    override fun createSections(): List<DataTableSection> {
        val totalAmount = data.values.sumOf { it }
        val rows = data.entries
            .sortedByDescending { it.value }
            .take(maxItems)
            .map { (country, amount) ->
                SimpleDateTableRow(
                    columnCountry to country,
                    columnSales to amount.withCurrency(Currency.USD),
                    columnSalesPercentage to PercentageValue.of(amount, totalAmount)
                )
            }

        return listOf(
            SimpleTableSection(
                rows, footer = SimpleTableSection(
                    SimpleDateTableRow(
                        columnCountry to "${data.size} countries",
                        columnSalesPercentage to PercentageValue(BigDecimal(100.0))
                    )
                )
            )
        )
    }
}