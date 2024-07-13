/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.topCountries

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.PluginSale
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import dev.ja.marketplace.data.trackers.SimpleTrialTracker
import dev.ja.marketplace.data.trackers.TrialTracker
import dev.ja.marketplace.data.trackers.getResultByTrialDuration
import dev.ja.marketplace.util.sortValue
import java.util.*

private data class CountryData(
    var totalSales: MonetaryAmountTracker,
    var salesCount: Int = 0,
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
    private fun String.orEmptyCountry(): String = ifEmpty { NoValue }

    private var maxTrialDays: Int = Marketplace.MAX_TRIAL_DAYS_DEFAULT

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
    private val columnTrialConversion = DataTableColumn(
        "trials-converted",
        "Converted Trials",
        cssClass = "num num-percentage",
        tooltip = "Percentage of trials which turned into a subscription after the trial started"
    )

    private val countries = TreeMap<String, CountryData>()
    private val allTrialsTracker: TrialTracker = SimpleTrialTracker()
    private lateinit var allSalesTracker: MonetaryAmountTracker

    override val columns: List<DataTableColumn> = listOfNotNull(
        columnCountry,
        columnSales,
        columnSalesPercentage,
        columnTrialsPercentage.takeIf { showTrials },
        columnTrialCount.takeIf { showTrials },
        columnTrialConversion.takeIf { showTrials },
    )

    override suspend fun init(data: PluginData) {
        super.init(data)

        allSalesTracker = MonetaryAmountTracker(data.exchangeRates)
        maxTrialDays = data.pluginInfo.purchaseInfo?.trialPeriod ?: Marketplace.MAX_TRIAL_DAYS_DEFAULT

        allTrialsTracker.init(data.trials ?: emptyList())
        if (data.trials != null) {
            for ((country, trials) in data.trials.groupBy { it.customer.country.orEmptyCountry() }) {
                val countryData = countries.computeIfAbsent(country) { CountryData(MonetaryAmountTracker(data.exchangeRates)) }
                countryData.trials.init(trials)
            }
        }
    }

    override suspend fun process(sale: PluginSale) {
        allTrialsTracker.processSale(sale)
        countries[sale.customer.country.orEmptyCountry()]?.trials?.processSale(sale)
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        allSalesTracker.add(licenseInfo.sale.date, licenseInfo.amountUSD, licenseInfo.amount)

        val countryData = countries.getOrPut(licenseInfo.sale.customer.country.orEmptyCountry()) {
            CountryData(MonetaryAmountTracker(exchangeRates))
        }
        countryData.salesCount += 1
        countryData.totalSales.add(licenseInfo.sale.date, licenseInfo.amountUSD, licenseInfo.amount)
    }

    override suspend fun createSections(): List<DataTableSection> {
        val totalSalesAmount = allSalesTracker.getTotalAmount()

        val trialsAnyDuration = allTrialsTracker.getResult(YearMonthDayRange.MAX)
        val trialsTrialDuration = allTrialsTracker.getResultByTrialDuration(YearMonthDayRange.MAX, maxTrialDays)

        val rows = countries.entries
            .sortedByDescending { it.value.totalSales.getTotalAmount() }
            .take(maxItems ?: Int.MAX_VALUE)
            .map { (country, countryData) ->
                val totalSales = countryData.totalSales.takeIf { countryData.salesCount > 0 }
                val salesPercentage = PercentageValue.of(countryData.totalSales.getTotalAmount(), totalSalesAmount)

                val countryTrialsAnyDuration = countryData.trials.getResult(YearMonthDayRange.MAX)
                val countryTrialsTrialDuration = countryData.trials.getResultByTrialDuration(YearMonthDayRange.MAX, maxTrialDays)
                val trialPercentage = PercentageValue.of(countryTrialsAnyDuration.totalTrials, trialsAnyDuration.totalTrials)
                val trialConversionRate = countryTrialsAnyDuration.convertedTrialsPercentage

                val totalAmount = totalSales?.getTotalAmount()
                SimpleDateTableRow(
                    values = mapOf(
                        columnCountry to country,
                        columnSales to (totalAmount ?: NoValue),
                        columnSalesPercentage to salesPercentage,
                        columnTrialCount to (countryTrialsAnyDuration.totalTrials.takeUnless { it == 0 }?.toBigInteger() ?: NoValue),
                        columnTrialsPercentage to trialPercentage,
                        columnTrialConversion to trialConversionRate,
                    ),
                    sortValues = mapOf(
                        columnSales to (totalAmount?.sortValue() ?: -1L),
                        columnSalesPercentage to salesPercentage.value.toLong(),
                        columnTrialCount to countryTrialsAnyDuration.totalTrials.toLong(),
                        columnTrialsPercentage to trialPercentage.value.toLong(),
                        columnTrialConversion to trialConversionRate.value.toLong(),
                    ),
                    tooltips = mapOf(
                        columnTrialConversion to countryTrialsAnyDuration.getTooltipConverted() +
                                "\n" +
                                countryTrialsTrialDuration.getTooltipConverted(maxTrialDays)
                    )
                )
            }

        val countriesWithSales = this.countries.values.count { it.salesCount > 0 }
        val countriesWithTrials = this.countries.values.count {
            it.trials.getResultByTrialDuration(YearMonthDayRange.MAX, maxTrialDays).totalTrials > 0
        }

        return listOf(
            SimpleTableSection(
                rows,
                footer = when {
                    maxItems != null -> SimpleTableSection(
                        SimpleDateTableRow(
                            columnCountry to "${countries.size} countries",
                        )
                    )

                    else -> SimpleTableSection(
                        SimpleDateTableRow(
                            values = mapOf(
                                columnCountry to listOf(
                                    "${countries.size} countries",
                                    "$countriesWithSales with sales",
                                    "$countriesWithTrials with trials",
                                ),
                                columnSales to totalSalesAmount,
                                columnTrialCount to trialsAnyDuration.totalTrials.toBigInteger(),
                                columnTrialConversion to trialsAnyDuration.convertedTrialsPercentage,
                            ),
                            tooltips = mapOf(
                                columnTrialConversion to trialsAnyDuration.getTooltipConverted() +
                                        "\n" +
                                        trialsTrialDuration.getTooltipConverted(maxTrialDays)
                            )
                        )
                    )
                }
            )
        )
    }
}