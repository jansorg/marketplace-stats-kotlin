/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.prices

import dev.ja.marketplace.client.CaffeineCacheMarketplaceClient
import dev.ja.marketplace.client.HttpClientFactory
import dev.ja.marketplace.client.currency.CurrencyInfo
import dev.ja.marketplace.client.model.PluginPriceInfo
import dev.ja.marketplace.client.model.PluginPriceInfoByType
import dev.ja.marketplace.client.model.PriceInfoByPeriod
import dev.ja.marketplace.client.model.PriceInfoData
import dev.ja.marketplace.client.model.PriceInfoTypeData
import dev.ja.marketplace.data.PluginPricing
import dev.ja.marketplace.services.Countries
import dev.ja.marketplace.services.Country
import dev.ja.marketplace.services.CountryWithCurrency
import dev.ja.marketplace.services.Currency
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class PluginPricingTest {
    private val prices = PluginPriceInfo(
        "",
        "",
        CurrencyInfo("EUR", "€", false),
        PluginPriceInfoByType(
            PriceInfoByPeriod(annual = PriceInfoTypeData(PriceInfoData(BigDecimal("50.0"), null, "shop-code"))),
            PriceInfoByPeriod(annual = PriceInfoTypeData(PriceInfoData(BigDecimal("100.0"), null, "shop-code"))),
        ),
    )

    private val countries = Countries(listOf("DE", "AT", "FR").map { iso ->
        CountryWithCurrency(Country(iso, iso, iso, true, "", ""), Currency("EUR", "€", false))
    })

    // prices in Germany, no prices in Austria and an error of the server in France
    private val client = CaffeineCacheMarketplaceClient(
        httpClientBuilder = HttpClientFactory.builder().withRequestRetry(false).withEngine(MockEngine { request ->
            when (request.url.parameters["countryCode"]) {
                "DE" -> respond(
                    Json.encodeToString(PluginPriceInfo.serializer(), prices),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )

                "AT" -> respondError(HttpStatusCode.NotFound)
                else -> respondError(HttpStatusCode.InternalServerError)
            }
        }),
    )

    private val pricing = PluginPricing(client, 1, countries)

    @Test
    suspend fun pricesOfACountry() {
        assertEquals(prices.prices, pricing.getCountryPricing("DE")?.prices)
    }

    @Test
    suspend fun countryWithoutPrices() {
        assertNull(pricing.getCountryPricing("AT"))
    }

    @Test
    suspend fun unknownCountry() {
        assertNull(pricing.getCountryPricing("XX"))
    }

    @Test
    suspend fun otherErrorsFail() {
        assertThrows<ServerResponseException> { pricing.getCountryPricing("FR") }
    }
}
