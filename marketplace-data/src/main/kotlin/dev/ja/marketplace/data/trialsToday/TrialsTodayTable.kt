/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trialsToday

import dev.ja.marketplace.client.Country
import dev.ja.marketplace.client.PluginTrial
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.*
import java.math.BigInteger

class TrialsTodayTable(private val now: YearMonthDay = YearMonthDay.now()) :
    SimpleDataTable("Trials Today", cssClass = "small table-striped"), MarketplaceDataSink {
    private lateinit var trials: List<PluginTrial>

    private val columnCountry = DataTableColumn("country", null, "col-right")
    private val columnCount = DataTableColumn("count", null, "num")

    override val columns: List<DataTableColumn> = listOf(columnCountry, columnCount)

    override fun init(data: PluginData) {
        trials = data.trials
            ?.filter { it.date == now }
            ?.sortedBy { it.customer.country }
            ?: emptyList()
    }

    override fun process(licenseInfo: LicenseInfo) {
        // ignored
    }

    override fun createSections(): List<DataTableSection> {
        val trialRows = trials
            .groupBy { it.customer.type }
            .mapValues { it.value.groupBy { it.customer.country } }
            .flatMap { (_, countryTrials) ->
                countryTrials.map { (country, trials) ->
                    SimpleDateTableRow(
                        columnCountry to (country.takeIf(Country::isNotEmpty) ?: "—"),
                        columnCount to trials.size.toBigInteger()
                    )
                }
            }
            .sortedByDescending { it.values[columnCount] as BigInteger }

        return listOf(
            SimpleTableSection(
                rows = trialRows,
                footer = SimpleRowGroup(
                    SimpleDateTableRow(
                        columnCount to trials.size,
                    )
                )
            )
        )
    }
}