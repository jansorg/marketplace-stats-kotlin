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
 * The monthly changes of the MRR, grouped by new, expansion, contraction, churned and reactivated revenue.
 */
class MrrMovementsTable : RecurringRevenueTable("MRR Movements", "mrr-movements", "table-striped") {
    private val columnMonth = DataTableColumn("month", null)
    private val columnMrr = DataTableColumn(
        "mrr", "MRR", "num",
        tooltip = "Monthly recurring revenue at the end of the month",
        chartMeasure = ChartMeasure.Stock,
    )
    private val columnNew = DataTableColumn(
        "new", "New", "num",
        tooltip = "MRR of customers, who paid for the first time",
        chartMeasure = ChartMeasure.Flow,
    )
    private val columnExpansion = DataTableColumn(
        "expansion", "Expansion", "num",
        tooltip = "Additional MRR of existing customers, e.g. more licenses or a higher price",
        chartMeasure = ChartMeasure.Flow,
    )
    private val columnContraction = DataTableColumn(
        "contraction", "Contraction", "num",
        tooltip = "Lost MRR of remaining customers, e.g. fewer licenses, a lower price or a higher discount",
        chartMeasure = ChartMeasure.Flow,
    )
    private val columnChurned = DataTableColumn(
        "churned", "Churned", "num",
        tooltip = "MRR of customers without recurring revenue at the end of the month",
        chartMeasure = ChartMeasure.Flow,
    )
    private val columnReactivation = DataTableColumn(
        "reactivation", "Reactivation", "num",
        tooltip = "MRR of returning customers, who had recurring revenue before, but not at the end of the previous month",
        chartMeasure = ChartMeasure.Flow,
    )
    private val columnNetChange = DataTableColumn(
        "net-change", "Net Change", "num",
        tooltip = "Change of the MRR compared to the previous month",
        chartMeasure = ChartMeasure.Flow,
    )
    private val columnQuickRatio = DataTableColumn(
        "quick-ratio", "Quick Ratio", "num",
        tooltip = "(New + Expansion + Reactivation) / (Contraction + Churned). Below 1 the MRR is shrinking.",
        chartMeasure = ChartMeasure.Ratio,
    )

    override val columns: List<DataTableColumn> = listOf(
        columnMonth, columnMrr, columnNew, columnExpansion, columnContraction, columnChurned, columnReactivation,
        columnNetChange, columnQuickRatio,
    )

    override fun createSections(revenue: CustomerRecurringRevenue): List<DataTableSection> {
        val rows = revenue.months.indices.reversed().map { index ->
            val movements = MrrMovements.of(revenue, index)
            with(revenue) {
                SimpleDateTableRow(
                    columnMonth to monthLabel(index),
                    columnMrr to valueOf(index, toAmount(movements.mrr)),
                    columnNew to valueOf(index, toAmount(movements.new)),
                    columnExpansion to valueOf(index, toAmount(movements.expansion)),
                    columnContraction to valueOf(index, toAmount(movements.contraction.negate())),
                    columnChurned to valueOf(index, toAmount(movements.churned.negate())),
                    columnReactivation to valueOf(index, toAmount(movements.reactivation)),
                    columnNetChange to valueOf(index, toAmount(movements.netChange)),
                    columnQuickRatio to valueOf(index, movements.quickRatio),
                )
            }
        }

        return listOf(SimpleTableSection(rows))
    }
}
