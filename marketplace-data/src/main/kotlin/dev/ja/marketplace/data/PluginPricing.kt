/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import com.github.benmanes.caffeine.cache.Caffeine
import dev.hsbrysk.caffeine.buildCoroutine
import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.CustomerInfo
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginPriceInfo
import dev.ja.marketplace.services.Countries
import dev.ja.marketplace.services.CountryIsoCode
import org.javamoney.moneta.FastMoney
import org.javamoney.moneta.Money
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
    private val basePriceCache = Caffeine.newBuilder()
        .maximumSize(500)
        .buildCoroutine<PricingCacheKey, MonetaryAmount>()

    suspend fun getCountryPricing(countryIsoCode: CountryIsoCode): PluginPriceInfo? {
        return when {
            countries.byCountryIsoCode(countryIsoCode) == null -> null
            else -> client.priceInfo(pluginId, countryIsoCode)
        }
    }

    suspend fun getBasePrice(
        customerInfo: CustomerInfo,
        licensePeriod: LicensePeriod,
        continuityDiscount: ContinuityDiscount,
    ): MonetaryAmount? {
        return basePriceCache.get(PricingCacheKey(customerInfo.country, customerInfo.type, licensePeriod, continuityDiscount)) { key ->
            getBasePriceInner(key.country, key.customerType, key.licensePeriod, key.continuityDiscount) ?: NoBasePriceValue
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

        val countryPricing = getCountryPricing(countryWithCurrency.country.isoCode) ?: return null
        val pricing = when (customerType) {
            CustomerType.Individual -> countryPricing.prices.personal
            CustomerType.Organization -> countryPricing.prices.commercial
        }

        val discountedPrice = when (continuityDiscount) {
            ContinuityDiscount.None,
            ContinuityDiscount.FirstYear -> when (licensePeriod) {
                LicensePeriod.Monthly -> pricing.monthly?.firstYear?.price
                LicensePeriod.Annual -> pricing.annual?.firstYear?.price
                LicensePeriod.Perpetual -> pricing.perpetual?.price
            }

            ContinuityDiscount.SecondYear -> when (licensePeriod) {
                LicensePeriod.Monthly -> pricing.monthly?.secondYear?.price
                LicensePeriod.Annual -> pricing.annual?.secondYear?.price
                LicensePeriod.Perpetual -> null
            }

            ContinuityDiscount.ThirdYear -> when (licensePeriod) {
                LicensePeriod.Monthly -> pricing.monthly?.secondYear?.price
                LicensePeriod.Annual -> pricing.annual?.secondYear?.price
                LicensePeriod.Perpetual -> null
            }
        } ?: return null

        return FastMoney.of(discountedPrice, countryWithCurrency.currency.isoCode)
    }

    companion object {
        fun create(client: MarketplaceClient, pluginId: PluginId, countries: Countries): PluginPricing {
            return PluginPricing(client, pluginId, countries)
        }
    }
}
