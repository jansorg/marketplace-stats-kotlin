/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trialsToday

import dev.ja.marketplace.client.Country
import dev.ja.marketplace.client.PluginTrial
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.*
import java.math.BigInteger

class TrialsTodayTable : SimpleDataTable("Trials Today", cssClass = "small table-striped"), MarketplaceDataSink {
    private val columnType = DataTableColumn("type", null, "col-right")
    private val columnCountry = DataTableColumn("country", null, "col-right")
    private val columnCount = DataTableColumn("count", null, "num")
    override val columns: List<DataTableColumn> = listOf(columnType, columnCountry, columnCount)

    private val now = YearMonthDay.now()
    private lateinit var trials: List<PluginTrial>

    override fun init(data: PluginData) {
        trials = data.trials
            ?.filter { it.date == now }
            ?.sortedBy { it.customer.country }
            ?: emptyList()
    }

    override fun process(licenseInfo: LicenseInfo) {
        // ignored
    }

    override val sections: List<DataTableSection>
        get() {
            val trialRows = trials
                .groupBy { it.customer.type }
                .mapValues { it.value.groupBy { it.customer.country } }
                .flatMap { (type, countryTrials) ->
                    countryTrials.map { (country, trials) ->
                        SimpleDateTableRow(
                            columnType to type,
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