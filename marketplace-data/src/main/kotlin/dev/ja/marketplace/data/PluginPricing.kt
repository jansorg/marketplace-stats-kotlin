/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.*
import dev.ja.marketplace.services.Countries
import dev.ja.marketplace.services.CountryIsoCode
import org.javamoney.moneta.FastMoney
import org.javamoney.moneta.Money
import java.util.concurrent.ConcurrentHashMap
import javax.money.MonetaryAmount

private data class PricingCacheKey(
    val country: String,
    val customerType: CustomerType,
    val licensePeriod: LicensePeriod,
    val continuityDiscount: ContinuityDiscount,
)

private val NoBasePriceValue = Money.of(-1111.11, MarketplaceCurrencies.USD)

data class PluginPricing(
    private val client: MarketplaceClient,
    private val pluginId: PluginId,
    private val countries: Countries,
) {
    private val pluginPriceCache = ConcurrentHashMap<CountryIsoCode, PluginPriceInfo>()
    private val basePriceCache = ConcurrentHashMap<PricingCacheKey, MonetaryAmount>()

    suspend fun getCountryPricing(countryIsoCode: CountryIsoCode): PluginPriceInfo? {
        if (countries.byCountryIsoCode(countryIsoCode) == null) {
            return null
        }

        return pluginPriceCache.getOrPut(countryIsoCode) {
            val info = client.priceInfo(pluginId, countryIsoCode)
            pluginPriceCache[countryIsoCode] = info
            info
        }
    }

    suspend fun getBasePrice(
        customerInfo: CustomerInfo,
        licensePeriod: LicensePeriod,
        continuityDiscount: ContinuityDiscount,
    ): MonetaryAmount? {
        val country = customerInfo.country
        val customerType = customerInfo.type
        return basePriceCache.getOrPut(PricingCacheKey(country, customerType, licensePeriod, continuityDiscount)) {
            getBasePriceInner(country, customerType, licensePeriod, continuityDiscount) ?: NoBasePriceValue
        }.takeIf { it !== NoBasePriceValue }
    }

    private suspend fun getBasePriceInner(
        customerCountry: String,
        customerType: CustomerType,
        licensePeriod: LicensePeriod,
        continuityDiscount: ContinuityDiscount
    ): MonetaryAmount? {
        val countryWithCurrency = countries.byCountryName(customerCountry)
            ?: throw IllegalStateException("unable to find country for name $customerCountry")

        val priceInfo = getCountryPricing(countryWithCurrency.country.isoCode) ?: return null

        val baseInfo = when (customerType) {
            CustomerType.Individual -> priceInfo.prices.personal
            CustomerType.Organization -> priceInfo.prices.commercial
        }

        val pricing = when (licensePeriod) {
            LicensePeriod.Monthly -> baseInfo.monthly
            LicensePeriod.Annual -> baseInfo.annual
        } ?: return null

        val withDiscount = when (continuityDiscount) {
            ContinuityDiscount.FirstYear -> pricing.firstYear
            ContinuityDiscount.SecondYear -> pricing.secondYear
            ContinuityDiscount.ThirdYear -> pricing.thirdYear
        }

        return FastMoney.of(withDiscount.price, countryWithCurrency.currency.isoCode)
    }

    companion object {
        fun create(client: MarketplaceClient, pluginId: PluginId, countries: Countries): PluginPricing {
            return PluginPricing(client, pluginId, countries)
        }
    }
}
