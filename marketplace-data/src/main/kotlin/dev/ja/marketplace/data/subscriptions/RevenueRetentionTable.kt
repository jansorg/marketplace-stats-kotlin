/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.data.ChartMeasure
import dev.ja.marketplace.data.DataTableColumn
import dev.ja.marketplace.data.DataTableSection
import dev.ja.marketplace.data.SimpleDateTableRow
import dev.ja.marketplace.data.SimpleTableSection

/**
 * The net and gross revenue retention and the customer retention of each month, compared to a year ago.
 */
class RevenueRetentionTable : RecurringRevenueTable("Revenue Retention", "revenue-retention", "table-striped table-column-medium") {
    private val columnMonth = DataTableColumn("month", null)
    private val columnCustomers = DataTableColumn(
        "customers", "Customers a Year Ago", "num",
        tooltip = "Customers with recurring revenue at the end of the same month in the previous year",
        chartMeasure = ChartMeasure.Stock,
    )
    private val columnCustomerRetention = DataTableColumn(
        "customer-retention", "Customer Retention", "num num-percentage",
        tooltip = "Share of these customers, who still have recurring revenue",
        chartMeasure = ChartMeasure.Ratio,
    )
    private val columnGrr = DataTableColumn(
        "grr", "GRR", "num num-percentage", chartTitle = "Gross Revenue Retention",
        tooltip = "Gross revenue retention: current MRR of the customers of a year ago, without expansion, " +
                "compared to their MRR a year ago. It's at most 100%.",
        chartMeasure = ChartMeasure.Ratio,
    )
    private val columnNrr = DataTableColumn(
        "nrr", "NRR", "num num-percentage", chartTitle = "Net Revenue Retention",
        tooltip = "Net revenue retention: current MRR of the customers of a year ago, including expansion, " +
                "compared to their MRR a year ago. Above 100% the existing customers pay more than a year ago.",
        chartMeasure = ChartMeasure.Ratio,
    )

    override val columns: List<DataTableColumn> = listOf(
        columnMonth, columnCustomers, columnCustomerRetention, columnGrr, columnNrr,
    )

    override fun createSections(revenue: CustomerRecurringRevenue): List<DataTableSection> {
        val rows = revenue.months.indices.reversed().mapNotNull { index ->
            val retention = RevenueRetention.of(revenue, index) ?: return@mapNotNull null
            with(revenue) {
                SimpleDateTableRow(
                    columnMonth to monthLabel(index),
                    columnCustomers to retention.previousCustomers,
                    columnCustomerRetention to valueOf(index, retention.customerRetention),
                    columnGrr to valueOf(index, retention.grossRevenueRetention),
                    columnNrr to valueOf(index, retention.netRevenueRetention),
                )
            }
        }

        return listOf(SimpleTableSection(rows))
    }
}
