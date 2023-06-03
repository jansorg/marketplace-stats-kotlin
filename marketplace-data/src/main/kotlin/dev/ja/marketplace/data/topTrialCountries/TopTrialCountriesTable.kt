/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.topTrialCountries

import dev.ja.marketplace.data.*
import dev.ja.marketplace.client.Country
import java.math.BigDecimal
import java.util.*

class TopTrialCountriesTable(private val maxItems: Int = 10) :
    SimpleDataTable("Top Trial Countries", "top-trial-countries"),
    MarketplaceDataSink {
    private val columnCountry = DataTableColumn("country", null)
    private val columnTrialCount = DataTableColumn("trials", null, "num")
    private val columnTrialsPercentage = DataTableColumn("trials", "% of Trials", "num num-percentage")

    private val data = TreeMap<Country, Int>()

    override val columns: List<DataTableColumn> = listOf(columnCountry, columnTrialCount, columnTrialsPercentage)

    override fun init(data: PluginData) {
        data.trials.forEach { trial ->
            if (trial.customer.country.isNotBlank()) {
                this.data.merge(trial.customer.country, 1, Int::plus)
            }
        }
    }

    override fun process(licenseInfo: LicenseInfo) {
        // empty
    }

    override val sections: List<DataTableSection>
        get() {
            val totalTrialCount = data.values.sumOf { it }
            val rows = data.entries
                .sortedByDescending { it.value }
                .take(maxItems)
                .map { (country, trialCount) ->
                    SimpleDateTableRow(
                        columnCountry to country,
                        columnTrialCount to trialCount,
                        columnTrialsPercentage to PercentageValue.of(
                            BigDecimal(trialCount),
                            BigDecimal(totalTrialCount)
                        )
                    )
                }

            return listOf(
                SimpleTableSection(
                    rows, footer = SimpleTableSection(
                        SimpleDateTableRow(
                            columnCountry to "${data.size} countries",
                            columnTrialsPercentage to PercentageValue(BigDecimal(100.0))
                        )
                    )
                )
            )
        }
}