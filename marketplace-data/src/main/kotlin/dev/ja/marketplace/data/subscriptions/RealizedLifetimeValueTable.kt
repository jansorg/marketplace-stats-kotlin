/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.ChartPeriod
import dev.ja.marketplace.data.DataTableColumn
import dev.ja.marketplace.data.DataTableSection
import dev.ja.marketplace.data.IncompleteValue
import dev.ja.marketplace.data.SimpleDateTableRow
import dev.ja.marketplace.data.SimpleTableSection
import java.math.BigDecimal

/**
 * The average amount, which the customers of each year paid within one, two, ... years.
 */
class RealizedLifetimeValueTable : RecurringRevenueTable(
    "Realized Lifetime Value", "realized-lifetime-value", "table-striped"
) {
    private val payments = mutableListOf<CustomerPayment>()

    private val columnYear = DataTableColumn(
        "cohort", null, tooltip = "Year of the first month with recurring revenue of the customers"
    )
    private val columnCustomers = DataTableColumn("customers", "Customers", "num")

    private var yearColumns: List<DataTableColumn> = emptyList()

    override val chartPeriod: ChartPeriod = ChartPeriod.Year

    override val columns: List<DataTableColumn>
        get() = listOf(columnYear, columnCustomers) + yearColumns

    override suspend fun process(sale: PluginSale) {
        val amount = sale.renderAmount(sale.date).number.numberValue(BigDecimal::class.java)
        payments += CustomerPayment(sale.customer.code, sale.date, amount)
    }

    override fun createSections(revenue: CustomerRecurringRevenue): List<DataTableSection> {
        val cohorts = RealizedLifetimeValue.of(revenue, payments)

        val maxYears = cohorts.maxOfOrNull { it.years.size } ?: 0
        yearColumns = (1..maxYears).map { years ->
            DataTableColumn(
                "year-$years", "Year $years", "num",
                chartTitle = "LTV after $years ${if (years == 1) "year" else "years"}",
                tooltip = "Average amount, which the customers paid until $years ${if (years == 1) "year" else "years"} " +
                        "after the start of their first month with recurring revenue"
            )
        }

        val rows = cohorts.reversed().map { cohort ->
            val values = mutableMapOf<DataTableColumn, Any?>(
                columnYear to cohort.year,
                columnCustomers to cohort.customers,
            )
            val tooltips = mutableMapOf<DataTableColumn, String?>()
            cohort.years.forEachIndexed { index, year ->
                val column = yearColumns[index]
                val average = revenue.toAmount(year.averageAmount)
                values[column] = if (year.isIncomplete) IncompleteValue(average) else average
                val total = revenue.toAmount(year.totalAmount)
                tooltips[column] = when (year.customers) {
                    cohort.customers -> "$total paid in total"
                    else -> "$total paid by ${year.customers} of ${cohort.customers} customers, " +
                            "whose first ${year.years} ${if (year.years == 1) "year" else "years"} ended"
                }
            }
            SimpleDateTableRow(values, tooltips)
        }

        return listOf(SimpleTableSection(rows, columns = columns))
    }
}
