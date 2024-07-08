/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.privingOverview

import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.*
import dev.ja.marketplace.services.Countries
import dev.ja.marketplace.services.CountryWithCurrency
import java.text.Collator

class PricingOverviewTable : SimpleDataTable("Pricing", "pricing", "table-column-wide"),
    MarketplaceDataSink {
    private lateinit var pluginPricing: PluginPricing
    private lateinit var countries: Countries

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
        this.countries = data.countries
        this.pluginPricing = data.pluginPricing!!
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

        val countryComparator = Comparator.comparing<Pair<CountryWithCurrency, PluginPriceInfo>, String>(
            { it.first.country.printableName },
            Collator.getInstance()
        )

        val currencyToPricing = MarketplaceCurrencies.associate { currency ->
            currency.isoCode to countries.byCurrencyIsoCode(currency.isoCode)!!.mapNotNull { countryWithCurrency ->
                val pricing = pluginPricing.getCountryPricing(countryWithCurrency.country.isoCode) ?: return@mapNotNull null
                countryWithCurrency to pricing
            }
        }

        val currencySections = currencyToPricing
            .entries
            .sortedByDescending { it.value.size } // show currencies with most entries first
            .map { (currencyCode, items) ->
                val subTableRows = items
                    .sortedWith(countryComparator)
                    .map { (countryWithCurrency, priceInfo) ->
                        val currency = countryWithCurrency.currency
                        val (personalA, personalB, personalC) = priceInfo.prices.personal.annual.mapYears(currency)
                        val (personalTaxedA, personalTaxedB, personalTaxedC) = priceInfo.prices.personal.annual.mapYearsTaxed(currency)

                        val (commercialA, commercialB, commercialC) = priceInfo.prices.commercial.annual.mapYears(currency)
                        val (commercialTaxedA, commercialTaxedB, commercialTaxedC) = priceInfo.prices.commercial.annual.mapYearsTaxed(
                            currency
                        )

                        SimpleDateTableRow(
                            values = mapOf(
                                columnCountry to countryWithCurrency.country.printableName,
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

                SimpleTableSection(rows = subTableRows, columns = subColumns, title = currencyCode)
            }

        return currencySections
    }

    private fun PriceInfoTypeData.mapYears(currency: dev.ja.marketplace.services.Currency): Triple<AmountWithCurrency, AmountWithCurrency, AmountWithCurrency> {
        return Triple(
            firstYear.price.withCurrency(currency),
            secondYear.price.withCurrency(currency),
            thirdYear.price.withCurrency(currency),
        )
    }

    private fun PriceInfoTypeData.mapYearsTaxed(currency: dev.ja.marketplace.services.Currency): Triple<AmountWithCurrency?, AmountWithCurrency?, AmountWithCurrency?> {
        return Triple(
            firstYear.priceTaxed?.withCurrency(currency),
            secondYear.priceTaxed?.withCurrency(currency),
            thirdYear.priceTaxed?.withCurrency(currency),
        )
    }
}