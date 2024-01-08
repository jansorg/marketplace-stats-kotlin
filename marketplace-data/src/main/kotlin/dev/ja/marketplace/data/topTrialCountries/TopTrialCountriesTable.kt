/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.topTrialCountries

import dev.ja.marketplace.client.Country
import dev.ja.marketplace.client.PluginSale
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.util.SimpleTrialTracker
import dev.ja.marketplace.data.util.TrialTracker
import java.math.BigDecimal
import java.util.*

class TopTrialCountriesTable(private val maxItems: Int = 10, private val smallSpaceFormat: Boolean) :
    SimpleDataTable(
        "Top Trial Countries",
        "top-trial-countries",
        if (smallSpaceFormat) "table-centered" else "table-centered sortable"
    ), MarketplaceDataSink {

    private val columnCountry = DataTableColumn("country", null, "col-right")
    private val columnTrialCount = DataTableColumn("trials", "Trials".takeUnless { smallSpaceFormat }, "num")
    private val columnTrialsPercentage = DataTableColumn(
        "trials",
        "% of Trials",
        "num num-percentage",
        tooltip = "Percentage of the total trials"
    )
    private val columnTrialConvertedPercentage = DataTableColumn(
        "trials-converted",
        if (smallSpaceFormat) "% Conv." else "Converted Trials",
        cssClass = "num num-percentage",
        tooltip = "Percentage of trials which turned into a subscription after the trial started"
    )

    private val data = TreeMap<Country, Int>()
    private val countryTrialConversion = TreeMap<Country, TrialTracker>()
    private val allTrialsTracker: TrialTracker = SimpleTrialTracker()

    override val columns: List<DataTableColumn> = listOfNotNull(
        columnCountry,
        columnTrialCount,
        columnTrialsPercentage,
        columnTrialConvertedPercentage.takeUnless { smallSpaceFormat }
    )

    override fun init(data: PluginData) {
        data.trials?.forEach { trial ->
            allTrialsTracker.registerTrial(trial)

            val country = trial.customer.country
            if (!smallSpaceFormat || country.isNotEmpty()) {
                this.data.merge(country, 1, Int::plus)
                this.countryTrialConversion.getOrPut(country, ::SimpleTrialTracker).registerTrial(trial)
            }
        }
    }

    override fun process(sale: PluginSale) {
        allTrialsTracker.processSale(sale)

        val country = sale.customer.country
        if (country in countryTrialConversion) {
            countryTrialConversion[country]!!.processSale(sale)
        }
    }

    override fun process(licenseInfo: LicenseInfo) {
        // empty
    }

    override fun createSections(): List<DataTableSection> {
        val totalTrialCount = data.values.sumOf { it }
        val rows = data.entries
            .sortedByDescending { it.value }
            .take(maxItems)
            .map { (country, trialCount) ->
                val trialPercentage = PercentageValue.of(trialCount, totalTrialCount)
                val trialConversion = countryTrialConversion[country]!!.getResult().convertedTrialsPercentage
                SimpleDateTableRow(
                    values = mapOf(
                        columnCountry to country.ifEmpty { "—" },
                        columnTrialCount to trialCount.toBigInteger(),
                        columnTrialsPercentage to trialPercentage,
                        columnTrialConvertedPercentage to trialConversion,
                    ),
                    sortValues = mapOf(
                        columnTrialCount to trialCount.toLong(),
                        columnTrialsPercentage to trialPercentage.value.toLong(),
                        columnTrialConvertedPercentage to trialConversion.value.toLong(),
                    ),
                )
            }

        return listOf(
            SimpleTableSection(
                rows,
                footer = SimpleTableSection(
                    SimpleDateTableRow(
                        columnCountry to "${data.size} countries",
                        columnTrialCount to data.values.sum(),
                        columnTrialsPercentage to PercentageValue(BigDecimal(100.0)),
                        columnTrialConvertedPercentage to allTrialsTracker.getResult().convertedTrialsPercentage,
                    )
                )
            )
        )
    }
}