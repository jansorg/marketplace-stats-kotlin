/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.topTrialCountries

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.PluginSale
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.SimpleTrialTracker
import dev.ja.marketplace.data.trackers.TrialTracker
import dev.ja.marketplace.data.trackers.getResultByTrialDuration
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import java.math.BigDecimal

class TopTrialCountriesTable(
    private val maxItems: Int?,
    private val smallSpaceFormat: Boolean,
    private val showEmptyCountry: Boolean,
) :
    SimpleDataTable(
        "Top Trial Countries",
        "top-trial-countries",
        if (smallSpaceFormat) "table-centered" else "table-centered sortable"
    ), MarketplaceDataSink {

    private var maxTrialsDays: Int = Marketplace.MAX_TRIAL_DAYS_DEFAULT

    private val columnCountry = DataTableColumn("country", null, "col-right")
    private val columnTrialCount = DataTableColumn("trials", "Trials".takeUnless { smallSpaceFormat }, "num")
    private val columnTrialsPercentage = DataTableColumn(
        "trials",
        "% of Trials",
        "num num-percentage",
        tooltip = "Percentage of the total trials"
    )
    private val columnTrialConversionRate = DataTableColumn(
        "trials-converted",
        "Converted Trials",
        cssClass = "num num-percentage",
        tooltip = "Percentage of trials which turned into a subscription after the trial started"
    )

    private val countryToTrialCount = Object2IntOpenHashMap<String>()
    private val countryToTrialTracker = mutableMapOf<String, TrialTracker>()
    private val allTrialsTracker: TrialTracker = SimpleTrialTracker()

    override val columns: List<DataTableColumn> = listOfNotNull(
        columnCountry,
        columnTrialCount,
        columnTrialsPercentage,
        columnTrialConversionRate.takeUnless { smallSpaceFormat }
    )

    override suspend fun init(data: PluginData) {
        super.init(data)

        maxTrialsDays = data.pluginInfo.purchaseInfo?.trialPeriod ?: Marketplace.MAX_TRIAL_DAYS_DEFAULT
        allTrialsTracker.init(data.trials ?: emptyList())

        if (data.trials != null) {
            for ((country, trials) in data.trials.groupBy { it.customer.country }) {
                if (country.isNotEmpty() || showEmptyCountry) {
                    countryToTrialCount.addTo(country, trials.size)
                    countryToTrialTracker.getOrPut(country.orEmptyCountry(), ::SimpleTrialTracker).init(trials)
                }
            }
        }
    }

    override suspend fun process(sale: PluginSale) {
        allTrialsTracker.processSale(sale)

        val country = sale.customer.country
        if (showEmptyCountry || country.isNotEmpty()) {
            countryToTrialTracker[country.orEmptyCountry()]?.processSale(sale)
        }
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        // empty
    }

    override suspend fun createSections(): List<DataTableSection> {
        val totalTrialCount = countryToTrialCount.values.sumOf { it }
        val rows = countryToTrialCount.object2IntEntrySet()
            .sortedByDescending { it.intValue }
            .take(maxItems ?: Int.MAX_VALUE)
            .map { (country, trialCount) ->
                val trialPercentage = PercentageValue.of(trialCount, totalTrialCount)
                val trialConversion = countryToTrialTracker[country]!!.getResultByTrialDuration(
                    YearMonthDayRange.MAX,
                    maxTrialsDays
                ).convertedTrialsPercentage
                SimpleDateTableRow(
                    values = mapOf(
                        columnCountry to country,
                        columnTrialCount to trialCount.toBigInteger(),
                        columnTrialsPercentage to trialPercentage,
                        columnTrialConversionRate to trialConversion,
                    ),
                    sortValues = mapOf(
                        columnTrialCount to trialCount.toLong(),
                        columnTrialsPercentage to trialPercentage.value.toLong(),
                        columnTrialConversionRate to trialConversion.value.toLong(),
                    ),
                )
            }

        val trialsAnyDuration = allTrialsTracker.getResult(YearMonthDayRange.MAX)
        val trialsTrialDuration = allTrialsTracker.getResultByTrialDuration(YearMonthDayRange.MAX, totalTrialCount)
        return listOf(
            SimpleTableSection(
                rows,
                footer = SimpleTableSection(
                    SimpleDateTableRow(
                        values = mapOf(
                            columnCountry to "${countryToTrialCount.size} countries",
                            columnTrialCount to countryToTrialCount.values.sum().toBigInteger(),
                            columnTrialsPercentage to PercentageValue(BigDecimal(100.0)),
                            columnTrialConversionRate to trialsAnyDuration.convertedTrialsPercentage,
                        ),
                        tooltips = mapOf(
                            columnTrialConversionRate to trialsAnyDuration.getTooltipConverted() +
                                    "\n" +
                                    trialsTrialDuration.getTooltipConverted(maxTrialsDays)
                        )
                    )
                )
            )
        )
    }
}

private fun String.orEmptyCountry(): String {
    return ifEmpty { NoValue }
}
