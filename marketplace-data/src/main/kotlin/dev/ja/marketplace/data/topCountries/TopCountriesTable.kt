/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.topCountries

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import dev.ja.marketplace.data.trackers.SimpleTrialTracker
import dev.ja.marketplace.data.trackers.TrialTracker
import dev.ja.marketplace.data.trackers.getResultWithinConversionPeriod
import dev.ja.marketplace.data.trackers.conversionTooltip
import dev.ja.marketplace.data.trackers.maxTrialDays
import dev.ja.marketplace.util.sortValue
import java.math.BigDecimal
import java.util.*

private data class CountryData(
    var totalSales: MonetaryAmountTracker,
    var salesCount: Int = 0,
    var trials: TrialTracker = SimpleTrialTracker(),
)

class TopCountriesTable(
    smallSpace: Boolean,
    private val maxItems: Int?,
    private val showTrials: Boolean,
    private val showChurn: Boolean = false,
    /** The number of rows shown before a click on "Show all", `null` to show all rows. */
    override val collapsedRowCount: Int? = null,
) : SimpleDataTable(
    if (maxItems == null) "" else "Top Countries",
    "top-countries",
    if (maxItems != null) "table-centered" else "table-centered sortable",
), MarketplaceDataSink {

    private fun String.fromEmptyCountry(): String = if (this == NoValue) "" else this

    private fun CountryChurn.Result.renderedChurnRate(): Any {
        val rate = churnRate ?: return NoValue
        // PercentageValue.ZERO is rendered as "no value", but no churn is a valid result
        return if (churned == 0) PercentageValue(BigDecimal.ZERO).toString() else rate
    }

    // countries without licenses a year ago are sorted after the countries without churn
    private fun CountryChurn.Result.sortValue(): Long = churnRate?.value?.movePointRight(2)?.toLong() ?: -1L

    private fun CountryChurn.Result.tooltip(): String? {
        return if (previouslyActive == 0) null else "$churned of $previouslyActive licenses churned"
    }

    private var maxTrialDays: Int = Marketplace.MAX_TRIAL_DAYS_DEFAULT

    private val columnCountry = DataTableColumn("country", if (smallSpace) null else "Country", "col-right", accessibleTitle = "Country")
    private val columnSales = DataTableColumn("sales", if (smallSpace) null else "Total Sales", "num", accessibleTitle = "Total Sales", preSorted = AriaSortOrder.Descending, chartTitle = "Total Sales")
    private val columnSalesPercentage = DataTableColumn("sales", "% of Sales", "num num-percentage", isShareOfTotal = true)
    private val columnTrialCount = DataTableColumn("trials", "Trials", "num")
    private val columnTrialsPercentage = DataTableColumn(
        "trials",
        "% of Trials",
        "num num-percentage",
        isShareOfTotal = true,
        tooltip = "Percentage of the total trials"
    )
    private val columnTrialConversion = DataTableColumn(
        "trials-converted",
        "Converted Trials",
        cssClass = "num num-percentage",
        tooltip = "Percentage of trials which turned into a subscription after the trial started"
    )

    private val columnChurnAnnual = DataTableColumn(
        "churn-annual-paid", "Churn (annual)", "num num-percentage",
        tooltip = "Churn of paid annual licenses in the 12 months before today: " +
                "the share of the licenses of the country, which were active a year ago and aren't active today"
    )
    private val columnChurnMonthly = DataTableColumn(
        "churn-monthly-paid", "Churn (monthly)", "num num-percentage",
        tooltip = "Churn of paid monthly licenses in the 12 months before today: " +
                "the share of the licenses of the country, which were active a year ago and aren't active today"
    )

    private val countries = TreeMap<String, CountryData>()
    private var churn: CountryChurn? = null
    private val allTrialsTracker: TrialTracker = SimpleTrialTracker()
    private lateinit var allSalesTracker: MonetaryAmountTracker

    override val columns: List<DataTableColumn> = listOfNotNull(
        columnCountry,
        columnSales,
        columnSalesPercentage,
        columnTrialsPercentage.takeIf { showTrials },
        columnTrialCount.takeIf { showTrials },
        columnTrialConversion.takeIf { showTrials },
        columnChurnAnnual.takeIf { showChurn },
        columnChurnMonthly.takeIf { showChurn },
    )

    override suspend fun init(data: PluginData) {
        super.init(data)

        allSalesTracker = MonetaryAmountTracker(data.exchangeRates)
        maxTrialDays = data.getPluginInfo().maxTrialDays
        if (showChurn) {
            churn = CountryChurn(data.getLicenses() ?: emptyList(), YearMonthDay.now())
        }

        val pluginTrials = data.getTrials() ?: emptyList()
        allTrialsTracker.init(pluginTrials)
        if (pluginTrials.isNotEmpty()) {
            for ((country, trials) in pluginTrials.groupBy { it.customer.country.orEmptyCountry() }) {
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
        val trialsTrialDuration = allTrialsTracker.getResultWithinConversionPeriod(YearMonthDayRange.MAX, maxTrialDays)

        val rows = countries.entries
            .sortedByDescending { it.value.totalSales.getTotalAmount() }
            .take(maxItems ?: Int.MAX_VALUE)
            .map { (country, countryData) ->
                val totalSales = countryData.totalSales.takeIf { countryData.salesCount > 0 }
                val salesPercentage = PercentageValue.of(countryData.totalSales.getTotalAmount(), totalSalesAmount)

                val countryTrialsAnyDuration = countryData.trials.getResult(YearMonthDayRange.MAX)
                val countryTrialsTrialDuration = countryData.trials.getResultWithinConversionPeriod(YearMonthDayRange.MAX, maxTrialDays)
                val trialPercentage = PercentageValue.of(countryTrialsAnyDuration.totalTrials, trialsAnyDuration.totalTrials)
                val trialConversionRate = countryTrialsTrialDuration.convertedTrialsPercentage

                val totalAmount = totalSales?.getTotalAmount()
                val churnAnnual = churn?.result(country.fromEmptyCountry(), LicensePeriod.Annual)
                val churnMonthly = churn?.result(country.fromEmptyCountry(), LicensePeriod.Monthly)
                SimpleDateTableRow(
                    values = mapOf(
                        columnCountry to country,
                        columnSales to (totalAmount ?: NoValue),
                        columnSalesPercentage to salesPercentage,
                        columnTrialCount to (countryTrialsAnyDuration.totalTrials.takeUnless { it == 0 }?.toBigInteger() ?: NoValue),
                        columnTrialsPercentage to trialPercentage,
                        columnTrialConversion to trialConversionRate,
                        columnChurnAnnual to churnAnnual?.renderedChurnRate(),
                        columnChurnMonthly to churnMonthly?.renderedChurnRate(),
                    ),
                    sortValues = mapOf(
                        columnSales to (totalAmount?.sortValue() ?: -1L),
                        columnSalesPercentage to salesPercentage.value.sortValue(),
                        columnTrialCount to countryTrialsAnyDuration.totalTrials.toLong(),
                        columnTrialsPercentage to trialPercentage.value.sortValue(),
                        columnTrialConversion to trialConversionRate.value.sortValue(),
                        columnChurnAnnual to churnAnnual?.sortValue(),
                        columnChurnMonthly to churnMonthly?.sortValue(),
                    ),
                    tooltips = mapOf(
                        columnChurnAnnual to churnAnnual?.tooltip(),
                        columnChurnMonthly to churnMonthly?.tooltip(),
                        columnTrialConversion to conversionTooltip(countryTrialsAnyDuration, countryTrialsTrialDuration, maxTrialDays)
                    )
                )
            }

        val countriesWithSales = this.countries.values.count { it.salesCount > 0 }
        val countriesWithTrials = this.countries.values.count {
            it.trials.getResult(YearMonthDayRange.MAX).totalTrials > 0
        }

        return listOf(
            SimpleTableSection(
                rows,
                footer = when {
                    maxItems != null -> SimpleTableSection(
                        SimpleDateTableRow(
                            columnCountry to "${countries.size} countries",
                            columnSales to totalSalesAmount,
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
                                columnTrialConversion to trialsTrialDuration.convertedTrialsPercentage,
                            ),
                            tooltips = mapOf(
                                columnTrialConversion to conversionTooltip(trialsAnyDuration, trialsTrialDuration, maxTrialDays)
                            )
                        )
                    )
                }
            )
        )
    }
}