/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.data.ChartPeriod
import dev.ja.marketplace.data.DataTableColumn
import dev.ja.marketplace.data.DataTableSection
import dev.ja.marketplace.data.IncompleteValue
import dev.ja.marketplace.data.NoValue
import dev.ja.marketplace.data.SimpleDateTableRow
import dev.ja.marketplace.data.SimpleTableSection

/**
 * The share of customers of each year, who still have recurring revenue after one, two, ... years.
 */
class CohortRetentionTable : RecurringRevenueTable("Cohorts", "customer-cohorts", "table-striped table-column-medium") {
    private val columnYear = DataTableColumn(
        "cohort", null,
        tooltip = "Customer cohorts: the customers grouped by the year of their first month with recurring revenue"
    )
    private val columnCustomers = DataTableColumn(
        "customers", "Customers", "num", tooltip = "Customers with their first month with recurring revenue in this year"
    )
    private val columnInitialMrr = DataTableColumn(
        "initial-mrr", "Initial MRR", "num", tooltip = "MRR of the customers in their first month"
    )

    private var anniversaryColumns: List<DataTableColumn> = emptyList()

    override val isChartable: Boolean = true
    override val chartPeriod: ChartPeriod = ChartPeriod.Year

    override val columns: List<DataTableColumn>
        get() = listOf(columnYear, columnCustomers, columnInitialMrr) + anniversaryColumns

    override fun createSections(revenue: CustomerRecurringRevenue): List<DataTableSection> {
        val cohorts = CohortRetention.of(revenue)

        val maxYears = cohorts.maxOfOrNull { it.anniversaries.size } ?: 0
        anniversaryColumns = (1..maxYears).map { years ->
            DataTableColumn(
                "year-$years", "Year $years", "num num-percentage",
                tooltip = "Share of the customers with recurring revenue $years ${if (years == 1) "year" else "years"} " +
                        "after their first month. Customers, whose anniversary is still in the future, aren't counted. " +
                        "The tooltips of the values show the share of the initial MRR."
            )
        }

        val rows = cohorts.reversed().map { cohort ->
            val values = mutableMapOf<DataTableColumn, Any?>(
                columnYear to cohort.year,
                columnCustomers to cohort.customers.toBigInteger(),
                columnInitialMrr to revenue.toAmount(cohort.initialMrr),
            )
            val tooltips = mutableMapOf<DataTableColumn, String?>()
            cohort.anniversaries.forEachIndexed { index, anniversary ->
                val column = anniversaryColumns[index]
                val retention = anniversary.customerRetention
                values[column] = retention?.let { if (anniversary.isIncomplete) IncompleteValue(it) else it }
                tooltips[column] = "${anniversary.retainedCustomers} of ${anniversary.customers} customers, " +
                        "MRR: ${anniversary.revenueRetention ?: NoValue} of the initial MRR"
            }
            SimpleDateTableRow(values, tooltips)
        }

        return listOf(SimpleTableSection(rows, columns = columns))
    }
}
