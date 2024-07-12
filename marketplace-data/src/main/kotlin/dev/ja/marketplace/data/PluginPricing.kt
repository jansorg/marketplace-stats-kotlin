/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.*
import dev.ja.marketplace.services.Countries
import org.javamoney.moneta.FastMoney
import org.javamoney.moneta.Money
import java.util.concurrent.ConcurrentHashMap
import javax.money.MonetaryAmount

private data class PricingCacheKey(
    val customerInfo: CustomerInfo,
    val licensePeriod: LicensePeriod,
    val continuityDiscount: ContinuityDiscount,
)

private val NoBasePriceValue = Money.of(-1111.11, MarketplaceCurrencies.USD)

data class PluginPricing(
    private val countries: Countries,
    private val countryCodeToPricing: Map<String, PluginPriceInfo>
) {
    private val basePriceCache = ConcurrentHashMap<PricingCacheKey, MonetaryAmount>()

    fun getCountryPricing(countryIsoCode: String): PluginPriceInfo? {
        return countryCodeToPricing[countryIsoCode]
    }

    fun getBasePrice(
        customerInfo: CustomerInfo,
        licensePeriod: LicensePeriod,
        continuityDiscount: ContinuityDiscount,
    ): MonetaryAmount? {
        return basePriceCache.computeIfAbsent(PricingCacheKey(customerInfo, licensePeriod, continuityDiscount)) {
            getBasePriceInner(customerInfo, licensePeriod, continuityDiscount) ?: NoBasePriceValue
        }.takeIf { it !== NoBasePriceValue }
    }

    private fun getBasePriceInner(
        customerInfo: CustomerInfo,
        licensePeriod: LicensePeriod,
        continuityDiscount: ContinuityDiscount
    ): MonetaryAmount? {
        val countryWithCurrency = countries.byCountryName(customerInfo.country)
            ?: throw IllegalStateException("unable to find country for name ${customerInfo.country}")
        val priceInfo = countryCodeToPricing[countryWithCurrency.country.isoCode] ?: return null

        val baseInfo = when (customerInfo.type) {
            CustomerType.Personal -> priceInfo.prices.personal
            CustomerType.Organization -> priceInfo.prices.commercial
        }
        val pricing = when (licensePeriod) {
            LicensePeriod.Monthly -> baseInfo.monthly
            LicensePeriod.Annual -> baseInfo.annual
        }
        val withDiscount = when (continuityDiscount) {
            ContinuityDiscount.FirstYear -> pricing.firstYear
            ContinuityDiscount.SecondYear -> pricing.secondYear
            ContinuityDiscount.ThirdYear -> pricing.thirdYear
        }

        return FastMoney.of(withDiscount.price, countryWithCurrency.currency.isoCode)
    }

    companion object {
        suspend fun create(countries: Countries, pluginId: PluginId, client: MarketplaceClient): PluginPricing {
            val currentPricing = mutableMapOf<String, PluginPriceInfo>()

            for (countryWithCurrency in countries) {
                val isoCode = countryWithCurrency.country.isoCode
                try {
                    val pricing = client.priceInfo(pluginId, isoCode)
                    currentPricing[isoCode] = pricing
                } catch (e: Exception) {
                    // ignore
                }
            }

            return PluginPricing(countries, currentPricing)
        }
    }
}
