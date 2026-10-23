/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.data.ChartPeriod
import dev.ja.marketplace.data.DataTableColumn
import dev.ja.marketplace.data.DataTableSection
import dev.ja.marketplace.data.IncompleteValue
import dev.ja.marketplace.data.SimpleDateTableRow
import dev.ja.marketplace.data.SimpleTableSection

/**
 * The share of customers of each year, who still have recurring revenue after one, two, ... years.
 */
class CohortRetentionTable : RecurringRevenueTable("Customer Cohorts", "customer-cohorts", "table-striped") {
    private val columnYear = DataTableColumn(
        "cohort", null, tooltip = "Year of the first month with recurring revenue of the customers"
    )
    private val columnCustomers = DataTableColumn("customers", "Customers", "num")
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
                        "after their first month. The tooltips of the values show the share of the initial MRR."
            )
        }

        val rows = cohorts.reversed().map { cohort ->
            val values = mutableMapOf<DataTableColumn, Any?>(
                columnYear to cohort.year,
                columnCustomers to cohort.customers,
                columnInitialMrr to revenue.toAmount(cohort.initialMrr),
            )
            val tooltips = mutableMapOf<DataTableColumn, String?>()
            cohort.anniversaries.forEachIndexed { index, anniversary ->
                val column = anniversaryColumns[index]
                val retention = anniversary.customerRetention
                values[column] = if (anniversary.isIncomplete) IncompleteValue(retention) else retention
                tooltips[column] = "${anniversary.retainedCustomers} of ${anniversary.customers} customers, " +
                        "MRR: ${anniversary.revenueRetention} of the initial MRR"
            }
            SimpleDateTableRow(values, tooltips)
        }

        return listOf(SimpleTableSection(rows, columns = columns))
    }
}
