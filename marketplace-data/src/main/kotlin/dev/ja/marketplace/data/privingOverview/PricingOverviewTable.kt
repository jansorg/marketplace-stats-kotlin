/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.privingOverview

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginPriceInfo
import dev.ja.marketplace.client.model.PriceInfoTypeData
import dev.ja.marketplace.data.*
import dev.ja.marketplace.services.Countries
import dev.ja.marketplace.services.CountryWithCurrency
import dev.ja.marketplace.services.Currency
import dev.ja.marketplace.util.sortValue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.javamoney.moneta.Money
import java.math.BigDecimal
import java.text.Collator
import javax.money.MonetaryAmount

/**
 * The current prices of the plugin in the countries, grouped by currency: the annual prices of the years of a
 * subscription, or the perpetual prices of a plugin with perpetual licenses. The monthly prices are calculated from
 * the annual prices by the Marketplace.
 */
class PricingOverviewTable : SimpleDataTable("Pricing", "pricing", "table-column-wide"),
    MarketplaceDataSink {
    private lateinit var pluginPricing: PluginPricing
    private lateinit var countries: Countries

    // the Marketplace offers either subscriptions or perpetual licenses
    private var isPerpetual = false

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

    private val columnPerpetualPersonal = DataTableColumn("pricing-perpetual", "Perpetual", "num highlighted")
    private val columnPerpetualCommercial = DataTableColumn("pricing-perpetual-commercial", "Perpetual", "num")

    override val columns: List<DataTableColumn>
        get() = when {
            isPerpetual -> listOf(columnEmpty, columnPersonal.copy(columnSpan = 1), columnCommercial.copy(columnSpan = 1))
            else -> listOf(columnEmpty, columnPersonal, columnCommercial)
        }

    override val alwaysShowMainColumns: Boolean = true

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.countries = data.countries
        this.pluginPricing = data.pluginPricing!!
        this.isPerpetual = data.getOfferedLicensePeriods()?.let { LicensePeriod.Perpetual in it && LicensePeriod.Annual !in it } ?: false
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        // ignored
    }

    override suspend fun createSections(): List<DataTableSection> {

        val countryComparator = Comparator.comparing<Pair<CountryWithCurrency, PluginPriceInfo>, String>(
            { it.first.country.printableName },
            Collator.getInstance()
        )

        // the prices of each country are a request, they're loaded concurrently
        val currencyToPricing = coroutineScope {
            MarketplaceCurrencies.map { currency ->
                // a currency without countries, e.g. after a change of the countries of JetBrains, has no prices
                currency to countries.byCurrencyIsoCode(currency.currencyCode).orEmpty().map { countryWithCurrency ->
                    async { pluginPricing.getCountryPricing(countryWithCurrency.country.isoCode)?.let { countryWithCurrency to it } }
                }
            }.associate { (currency, prices) -> currency to prices.awaitAll().filterNotNull() }
        }

        // without the offered license periods, the prices show if the plugin has perpetual licenses
        val prices = currencyToPricing.values.flatten().map { it.second.prices }
        if (!isPerpetual && prices.none { it.personal.annual != null } && prices.any { it.personal.perpetual != null }) {
            isPerpetual = true
        }
        val subColumns = when {
            isPerpetual -> listOf(columnCountry, columnPerpetualPersonal, columnPerpetualCommercial)
            else -> listOf(
                columnCountry,
                columnFirstYearPersonal,
                columnSecondYearPersonal,
                columnThirdYearPersonal,
                columnFirstYearCommercial,
                columnSecondYearCommercial,
                columnThirdYearCommercial,
            )
        }

        val currencySections = currencyToPricing
            .entries
            .sortedByDescending { it.value.size } // show currencies with most entries first
            .map { (currencyCode, items) ->
                val subTableRows = items
                    .sortedWith(countryComparator)
                    .map { (countryWithCurrency, priceInfo) ->
                        val currency = countryWithCurrency.currency
                        if (isPerpetual) {
                            return@map perpetualRow(countryWithCurrency, priceInfo, currency)
                        }
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
                                columnFirstYearPersonal to personalA?.sortValue(),
                                columnSecondYearPersonal to personalB?.sortValue(),
                                columnThirdYearPersonal to personalC?.sortValue(),
                                columnFirstYearCommercial to commercialA?.sortValue(),
                                columnSecondYearCommercial to commercialB?.sortValue(),
                                columnThirdYearCommercial to commercialC?.sortValue(),
                            )
                        )
                    }

                SimpleTableSection(rows = subTableRows, columns = subColumns, title = currencyCode.currencyCode)
            }

        return currencySections
    }

    private fun perpetualRow(countryWithCurrency: CountryWithCurrency, priceInfo: PluginPriceInfo, currency: Currency): SimpleDateTableRow {
        val personal = priceInfo.prices.personal.perpetual
        val commercial = priceInfo.prices.commercial.perpetual
        return SimpleDateTableRow(
            values = mapOf(
                columnCountry to countryWithCurrency.country.printableName,
                columnPerpetualPersonal to listOfNotNull(personal?.price?.withCurrency(currency), personal?.priceTaxed?.withCurrency(currency)),
                columnPerpetualCommercial to listOfNotNull(commercial?.price?.withCurrency(currency), commercial?.priceTaxed?.withCurrency(currency)),
            ),
            sortValues = mapOf(
                columnPerpetualPersonal to personal?.price?.withCurrency(currency)?.sortValue(),
                columnPerpetualCommercial to commercial?.price?.withCurrency(currency)?.sortValue(),
            )
        )
    }

    private fun PriceInfoTypeData?.mapYears(currency: Currency): Triple<MonetaryAmount?, MonetaryAmount?, MonetaryAmount?> {
        if (this == null) {
            return Triple(null, null, null)
        }

        return Triple(
            firstYear.price.withCurrency(currency),
            secondYear?.price?.withCurrency(currency),
            thirdYear?.price?.withCurrency(currency),
        )
    }

    private fun PriceInfoTypeData?.mapYearsTaxed(currency: Currency): Triple<MonetaryAmount?, MonetaryAmount?, MonetaryAmount?> {
        if (this == null) {
            return Triple(null, null, null)
        }

        return Triple(
            firstYear.priceTaxed?.withCurrency(currency),
            secondYear?.priceTaxed?.withCurrency(currency),
            thirdYear?.priceTaxed?.withCurrency(currency),
        )
    }

    private fun BigDecimal.withCurrency(currency: Currency): MonetaryAmount {
        return Money.of(this, currency.isoCode)
    }
}