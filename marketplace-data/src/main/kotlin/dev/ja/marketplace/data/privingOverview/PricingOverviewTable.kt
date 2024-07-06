/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.privingOverview

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.Currency
import dev.ja.marketplace.data.*
import java.text.Collator
import java.util.*

class PricingOverviewTable(private val client: MarketplaceClient) : SimpleDataTable("Pricing", "pricing", "table-column-wide"),
    MarketplaceDataSink {
    private val countryPricing = mutableListOf<Pair<Locale, PluginPriceInfo>>()

    private val columnEmpty = DataTableColumn("pricing-empty", "", columnSpan = 2)
    private val columnPersonal = DataTableColumn("pricing-personal", "Personal", columnSpan = 3)
    private val columnCommercial = DataTableColumn("pricing-personal", "Commercial", columnSpan = 3)

    private val columnCountry = DataTableColumn("pricing-country", null, preSorted = AriaSortOrder.Ascending)
    private val columnFirstYearPersonal = DataTableColumn("pricing-first-year", "1st Year", "num highlighted")
    private val columnSecondYearPersonal = DataTableColumn("pricing-second-year", "2nd Year", "num highlighted")
    private val columnThirdYearPersonal = DataTableColumn("pricing-third-year", "3rd Year", "num highlighted")
    private val columnFirstYearCommercial = DataTableColumn("pricing-first-year-commercial", "1st Year", "num")
    private val columnSecondYearCommercial = DataTableColumn("pricing-second-year-commercial", "2nd Year", "num")
    private val columnThirdYearCommercial = DataTableColumn("pricing-third-year-commercial", "3rd Year", "num")

    override val columns: List<DataTableColumn> = listOf(columnEmpty, columnPersonal, columnCommercial)

    override val alwaysShowMainColumns: Boolean = true

    override suspend fun init(data: PluginData) {
        val countryCodeToLocale = Locale.getAvailableLocales().associateBy { it.country }
        for ((countryCode, locale) in countryCodeToLocale) {
            try {
                val priceInfo = client.priceInfo(data.pluginId, countryCode)
                countryPricing += locale to priceInfo
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    override fun process(licenseInfo: LicenseInfo) {
        // ignored
    }

    override fun createSections(): List<DataTableSection> {
        val subColumns = listOf(
            columnCountry,
            columnFirstYearPersonal,
            columnSecondYearPersonal,
            columnThirdYearPersonal,
            columnFirstYearCommercial,
            columnSecondYearCommercial,
            columnThirdYearCommercial,
        )

        val countryComparator = Comparator.comparing<Pair<Locale, PluginPriceInfo>, String>(
            { it.first.displayCountry },
            Collator.getInstance()
        )

        val currencySections = countryPricing
            .groupBy { Currency.of(it.second.currency.currencyIsoId) }
            .entries
            .sortedByDescending { it.key }
            .sortedByDescending { it.value.size } // show currencies with most entries first
            .map { (currency, items) ->
                val subTableRows = items
                    .sortedWith(countryComparator)
                    .map { (countryLocale, pricing) ->
                        val (personalA, personalB, personalC) = pricing.prices.personal.annual.mapYears(currency)
                        val (personalTaxedA, personalTaxedB, personalTaxedC) = pricing.prices.personal.annual.mapYearsTaxed(currency)

                        val (commercialA, commercialB, commercialC) = pricing.prices.commercial.annual.mapYears(currency)
                        val (commercialTaxedA, commercialTaxedB, commercialTaxedC) = pricing.prices.commercial.annual.mapYearsTaxed(currency)

                        SimpleDateTableRow(
                            values = mapOf(
                                columnCountry to countryLocale.displayCountry,
                                columnFirstYearPersonal to listOfNotNull(personalA, personalTaxedA),
                                columnSecondYearPersonal to listOfNotNull(personalB, personalTaxedB),
                                columnThirdYearPersonal to listOfNotNull(personalC, personalTaxedC),

                                columnFirstYearCommercial to listOfNotNull(commercialA, commercialTaxedA),
                                columnSecondYearCommercial to listOfNotNull(commercialB, commercialTaxedB),
                                columnThirdYearCommercial to listOfNotNull(commercialC, commercialTaxedC),
                            ),
                            sortValues = mapOf(
                                columnFirstYearPersonal to personalA.amount.sortValue(),
                                columnSecondYearPersonal to personalB.amount.sortValue(),
                                columnThirdYearPersonal to personalC.amount.sortValue(),
                                columnFirstYearCommercial to commercialA.amount.sortValue(),
                                columnSecondYearCommercial to commercialB.amount.sortValue(),
                                columnThirdYearCommercial to commercialC.amount.sortValue(),
                            )
                        )
                    }

                SimpleTableSection(rows = subTableRows, columns = subColumns, title = currency.name)
            }

        return currencySections
    }

    private fun PriceInfoTypeData.mapYears(currency: Currency): Triple<AmountWithCurrency, AmountWithCurrency, AmountWithCurrency> {
        return Triple(
            firstYear.price.withCurrency(currency),
            secondYear.price.withCurrency(currency),
            thirdYear.price.withCurrency(currency),
        )
    }

    private fun PriceInfoTypeData.mapYearsTaxed(currency: Currency): Triple<AmountWithCurrency?, AmountWithCurrency?, AmountWithCurrency?> {
        return Triple(
            firstYear.priceTaxed?.withCurrency(currency),
            secondYear.priceTaxed?.withCurrency(currency),
            thirdYear.priceTaxed?.withCurrency(currency),
        )
    }
}