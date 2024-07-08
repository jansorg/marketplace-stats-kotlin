/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.topCountries

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.Currency
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.SimpleTrialTracker
import dev.ja.marketplace.data.trackers.TrialTracker
import java.util.*

private data class CountryData(
    var salesCount: Int = 0,
    var totalSales: Amount = Amount(0),
    var trials: TrialTracker = SimpleTrialTracker(),
)

class TopCountriesTable(
    smallSpace: Boolean,
    private val maxItems: Int?,
    private val showTrials: Boolean
) : SimpleDataTable(
    if (maxItems == null) "" else "Top Countries",
    "top-countries",
    if (maxItems != null) "table-centered" else "table-centered sortable",
), MarketplaceDataSink {
    private fun Country.orEmptyCountry(): Country = ifEmpty { NoValue }

    private val columnCountry = DataTableColumn("country", if (smallSpace) null else "Country", "col-right")
    private val columnSales = DataTableColumn("sales", if (smallSpace) null else "Total Sales", "num", preSorted = AriaSortOrder.Descending)
    private val columnSalesPercentage = DataTableColumn("sales", "% of Sales", "num num-percentage")
    private val columnTrialCount = DataTableColumn("trials", "Trials", "num")
    private val columnTrialsPercentage = DataTableColumn(
        "trials",
        "% of Trials",
        "num num-percentage",
        tooltip = "Percentage of the total trials"
    )
    private val columnTrialConvertedPercentage = DataTableColumn(
        "trials-converted",
        "Converted Trials",
        cssClass = "num num-percentage",
        tooltip = "Percentage of trials which turned into a subscription after the trial started"
    )

    private val data = TreeMap<Country, CountryData>()
    private val allTrialsTracker: TrialTracker = SimpleTrialTracker()

    override val columns: List<DataTableColumn> = listOfNotNull(
        columnCountry,
        columnSales,
        columnSalesPercentage,
        columnTrialsPercentage.takeIf { showTrials },
        columnTrialCount.takeIf { showTrials },
        columnTrialConvertedPercentage.takeIf { showTrials },
    )

    override suspend fun init(data: PluginData) {
        data.trials ?: return
        for (trial in data.trials) {
            allTrialsTracker.registerTrial(trial)

            val countryData = this.data.getOrPut(trial.customer.country.orEmptyCountry(), ::CountryData)
            countryData.trials.registerTrial(trial)
        }
    }

    override fun process(sale: PluginSale) {
        allTrialsTracker.processSale(sale)

        data[sale.customer.country.orEmptyCountry()]?.trials?.processSale(sale)
    }

    override fun process(licenseInfo: LicenseInfo) {
        val countryData = data.getOrPut(licenseInfo.sale.customer.country.orEmptyCountry(), ::CountryData)
        countryData.salesCount += 1
        countryData.totalSales += licenseInfo.amountUSD
    }

    override fun createSections(): List<DataTableSection> {
        val totalSalesAmount = data.values.sumOf(CountryData::totalSales)

        val allTrialsResult = allTrialsTracker.getResult()
        val totalTrialCount = allTrialsResult.totalTrials

        val rows = data.entries
            .sortedByDescending { it.value.totalSales }
            .take(maxItems ?: Int.MAX_VALUE)
            .map { (country, countryData) ->
                val totalSales = countryData.totalSales.takeIf { countryData.salesCount > 0 }
                val salesPercentage = PercentageValue.of(countryData.totalSales, totalSalesAmount)

                val trialsResult = countryData.trials.getResult()
                val trialPercentage = PercentageValue.of(trialsResult.totalTrials, totalTrialCount)
                val trialConversion = trialsResult.convertedTrialsPercentage

                SimpleDateTableRow(
                    values = mapOf(
                        columnCountry to country,
                        columnSales to (totalSales?.withCurrency(Currency.USD) ?: NoValue),
                        columnSalesPercentage to salesPercentage,
                        columnTrialCount to (trialsResult.totalTrials.takeUnless { it == 0 }?.toBigInteger() ?: NoValue),
                        columnTrialsPercentage to trialPercentage,
                        columnTrialConvertedPercentage to trialConversion,
                    ),
                    sortValues = mapOf(
                        columnSales to (totalSales?.toLong() ?: -1L),
                        columnSalesPercentage to salesPercentage.value.toLong(),
                        columnTrialCount to trialsResult.totalTrials.toLong(),
                        columnTrialsPercentage to trialPercentage.value.toLong(),
                        columnTrialConvertedPercentage to trialConversion.value.toLong(),
                    ),
                )
            }

        val countriesWithSales = this.data.values.count { it.salesCount > 0 }
        val countriesWithTrials = this.data.values.count { it.trials.getResult().totalTrials > 0 }
        return listOf(
            SimpleTableSection(
                rows,
                footer = when {
                    maxItems != null -> null
                    else -> SimpleTableSection(
                        SimpleDateTableRow(
                            columnCountry to listOf(
                                "${data.size} countries",
                                "$countriesWithSales with sales",
                                "$countriesWithTrials with trials",
                            ),
                            columnSales to totalSalesAmount.withCurrency(Currency.USD),
                            columnTrialCount to totalTrialCount.toBigInteger(),
                            columnTrialConvertedPercentage to allTrialsResult.convertedTrialsPercentage,
                        )
                    )
                }
            )
        )
    }
}