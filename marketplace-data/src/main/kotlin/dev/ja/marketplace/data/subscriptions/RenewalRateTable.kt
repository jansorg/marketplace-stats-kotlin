/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.data.DataTableColumn
import dev.ja.marketplace.data.DataTableSection
import dev.ja.marketplace.data.IncompleteValue
import dev.ja.marketplace.data.PercentageValue
import dev.ja.marketplace.data.SimpleDataTable
import dev.ja.marketplace.data.SimpleDateTableRow
import dev.ja.marketplace.data.SimpleTableSection

/**
 * The share of renewed subscriptions of each year, grouped by the license period and the number of previous renewals.
 */
class RenewalRateTable : SimpleDataTable("Renewal Rates", "renewal-rates", "table-striped") {
    private val licenses = mutableListOf<LicenseInfo>()

    private val columnYear = DataTableColumn(
        "year", null, tooltip = "Year, in which the subscriptions ended"
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

    override val columns: List<DataTableColumn> = listOf(columnYear, columnFirst, columnSecond, columnLater, columnAll)

    override suspend fun process(licenseInfo: LicenseInfo) {
        licenses += licenseInfo
    }

    override suspend fun createSections(): List<DataTableSection> {
        val rates = RenewalRates.of(licenses)
        val ageColumns = listOf(columnFirst, columnSecond, columnLater)

        return listOf(LicensePeriod.Annual, LicensePeriod.Monthly).mapNotNull { period ->
            val ratesByYear = rates.filter { it.licensePeriod == period }.groupBy { it.year }
            if (ratesByYear.isEmpty()) {
                return@mapNotNull null
            }

            val rows = ratesByYear.toSortedMap().entries.reversed().map { (year, yearRates) ->
                val values = mutableMapOf<DataTableColumn, Any?>(columnYear to year)
                val tooltips = mutableMapOf<DataTableColumn, String?>()
                for (rate in yearRates) {
                    val column = ageColumns[rate.previousRenewals]
                    values[column] = valueOf(rate.rate, rate.isIncomplete)
                    tooltips[column] = tooltip(rate.renewed, rate.due, rate.isIncomplete)
                }

                val due = yearRates.sumOf { it.due }
                val renewed = yearRates.sumOf { it.renewed }
                val isIncomplete = yearRates.any { it.isIncomplete }
                values[columnAll] = valueOf(PercentageValue.of(renewed, due), isIncomplete)
                tooltips[columnAll] = tooltip(renewed, due, isIncomplete)

                SimpleDateTableRow(values, tooltips)
            }

            SimpleTableSection(rows, title = "Renewal Rates of ${period.name} Licenses")
        }
    }

    private fun valueOf(rate: PercentageValue, isIncomplete: Boolean): Any {
        return if (isIncomplete) IncompleteValue(rate) else rate
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
