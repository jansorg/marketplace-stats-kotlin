/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.data.ChartMeasure
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.data.DataTableColumn
import dev.ja.marketplace.data.DataTableSection
import dev.ja.marketplace.data.SimpleDateTableRow
import dev.ja.marketplace.data.SimpleTableSection

/**
 * The average MRR per paying customer of each month, also for organizations and individuals.
 */
class AverageRevenuePerCustomerTable : RecurringRevenueTable(
    "Average Revenue per Customer", "average-revenue-per-customer", "table-striped table-column-medium"
) {
    private val columnMonth = DataTableColumn("month", null)
    private val columnCustomers = DataTableColumn(
        "customers", "Paying Customers", "num", tooltip = "Customers with recurring revenue at the end of the month",
        chartMeasure = ChartMeasure.Stock,
        dailyChartSeries = SubscriptionDailyColumn.PayingCustomers.id,
    )
    private val columnArpa = DataTableColumn(
        "arpa", "ARPA", "num", chartTitle = "Average MRR per Customer",
        tooltip = "Average MRR per paying customer",
        chartMeasure = ChartMeasure.Stock,
        dailyChartSeries = SubscriptionDailyColumn.AverageRevenue.id,
    )
    private val columnOrganizations = DataTableColumn(
        "organizations", "Organizations", "num", chartTitle = "Average MRR per Organization",
        tooltip = "Average MRR per paying organization",
        chartMeasure = ChartMeasure.Stock,
        dailyChartSeries = SubscriptionDailyColumn.AverageRevenueOrganizations.id,
    )
    private val columnIndividuals = DataTableColumn(
        "individuals", "Individuals", "num", chartTitle = "Average MRR per Individual",
        tooltip = "Average MRR per paying individual customer",
        chartMeasure = ChartMeasure.Stock,
        dailyChartSeries = SubscriptionDailyColumn.AverageRevenueIndividuals.id,
    )

    override val columns: List<DataTableColumn> = listOf(
        columnMonth, columnCustomers, columnArpa, columnOrganizations, columnIndividuals,
    )

    override fun createSections(revenue: CustomerRecurringRevenue): List<DataTableSection> {
        val rows = revenue.months.indices.reversed().map { index ->
            val average = AverageRevenuePerCustomer.of(revenue, index)
            with(revenue) {
                SimpleDateTableRow(
                    columnMonth to monthLabel(index),
                    columnCustomers to valueOf(index, average.customers.toBigInteger()),
                    columnArpa to valueOf(index, average.average?.let(::toAmount)),
                    columnOrganizations to valueOf(index, average.average(CustomerType.Organization)?.let(::toAmount)),
                    columnIndividuals to valueOf(index, average.average(CustomerType.Individual)?.let(::toAmount)),
                )
            }
        }

        return listOf(SimpleTableSection(rows))
    }
}
