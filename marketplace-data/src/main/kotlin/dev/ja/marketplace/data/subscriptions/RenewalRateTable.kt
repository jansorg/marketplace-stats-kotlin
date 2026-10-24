/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.data.ChartPeriod
import dev.ja.marketplace.data.DataTableColumn
import dev.ja.marketplace.data.DataTableSection
import dev.ja.marketplace.data.IncompleteValue
import dev.ja.marketplace.data.PercentageValue
import dev.ja.marketplace.data.SimpleDataTable
import dev.ja.marketplace.data.SimpleDateTableRow
import dev.ja.marketplace.data.SimpleTableSection

/**
 * The share of renewed subscriptions of a license period by year, grouped by the number of previous renewals.
 * Monthly subscriptions are shown by month, because they are renewed every month.
 */
class RenewalRateTable(private val licensePeriod: LicensePeriod) : SimpleDataTable(
    "Renewal Rates of ${licensePeriod.name} Licenses",
    "renewal-rates-${licensePeriod.name.lowercase()}",
    "table-striped table-column-medium",
) {
    private val licenses = mutableListOf<LicenseInfo>()
    private val isByMonth = licensePeriod == LicensePeriod.Monthly

    private val columnYear = DataTableColumn(
        if (isByMonth) "month" else "year", null,
        tooltip = if (isByMonth) "Month, in which the subscriptions ended" else "Year, in which the subscriptions ended",
    )
    private val columnFirst = DataTableColumn(
        "first", "1st Renewal", "num num-percentage", tooltip = "Renewal of new licenses"
    )
    private val columnSecond = DataTableColumn(
        "second", "2nd Renewal", "num num-percentage", tooltip = "Renewal of licenses, which were renewed once"
    )
    private val columnLater = DataTableColumn(
        "later", "Later Renewals", "num num-percentage", tooltip = "Renewal of licenses, which were renewed at least twice"
    )
    private val columnAll = DataTableColumn("all", "All", "num num-percentage")

    override val isChartable: Boolean = true
    override val chartPeriod: ChartPeriod = if (isByMonth) ChartPeriod.Month else ChartPeriod.Year

    // the renewal rates of months show the last year, the other months on demand
    override val collapsedRowCount: Int? = if (isByMonth) 12 else null

    override val columns: List<DataTableColumn> = listOf(columnYear, columnFirst, columnSecond, columnLater, columnAll)

    override suspend fun process(licenseInfo: LicenseInfo) {
        licenses += licenseInfo
    }

    override suspend fun createSections(): List<DataTableSection> {
        val ageColumns = listOf(columnFirst, columnSecond, columnLater)
        val ratesByPeriod = RenewalRates.of(licenses, byMonth = isByMonth)
            .filter { it.licensePeriod == licensePeriod }
            .groupBy { it.year to it.month }

        val rows = ratesByPeriod.entries.reversed().map { (period, periodRates) ->
            val (year, month) = period
            // a month like 2026-03, which charts recognize as month
            val label: Any = if (month == null) year else String.format("%04d-%02d", year, month)
            val values = mutableMapOf<DataTableColumn, Any?>(columnYear to label)
            val tooltips = mutableMapOf<DataTableColumn, String?>()
            for (rate in periodRates) {
                val column = ageColumns[rate.previousRenewals]
                values[column] = valueOf(rate.rate, rate.due, rate.isIncomplete)
                tooltips[column] = tooltip(rate.renewed, rate.due, rate.isIncomplete)
            }

            val due = periodRates.sumOf { it.due }
            val renewed = periodRates.sumOf { it.renewed }
            val isIncomplete = periodRates.any { it.isIncomplete }
            values[columnAll] = valueOf(PercentageValue.of(renewed, due), due, isIncomplete)
            tooltips[columnAll] = tooltip(renewed, due, isIncomplete)

            SimpleDateTableRow(values, tooltips)
        }

        return listOf(SimpleTableSection(rows))
    }

    /**
     * Without due subscriptions there's no rate, e.g. if all subscriptions of a month can still be renewed.
     * The cell is empty instead of 0%, which charts would show.
     */
    private fun valueOf(rate: PercentageValue, due: Int, isIncomplete: Boolean): Any? {
        return when {
            due == 0 -> null
            isIncomplete -> IncompleteValue(rate)
            else -> rate
        }
    }

    private fun tooltip(renewed: Int, due: Int, isIncomplete: Boolean): String {
        val pending = when {
            isIncomplete -> ", without subscriptions, which can still be renewed within " +
                    "${Marketplace.LICENSE_RENEWAL_GRACE_DAYS} days after they ended"

            else -> ""
        }
        return "$renewed of $due subscriptions renewed$pending"
    }
}
