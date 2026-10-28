/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.model.PluginPriceInfo
import dev.ja.marketplace.services.Countries
import dev.ja.marketplace.services.CountryIsoCode
import io.ktor.client.plugins.*
import io.ktor.http.*

/**
 * The prices of a plugin in the countries.
 */
class PluginPricing(
    private val client: MarketplaceClient,
    private val pluginId: PluginId,
    private val countries: Countries,
) {
    /**
     * @return The prices in the country, `null` if the country is unknown or if the plugin has no prices there.
     *  The Marketplace answers with 404 for some countries, they don't fail the prices of the other countries.
     */
    suspend fun getCountryPricing(countryIsoCode: CountryIsoCode): PluginPriceInfo? {
        if (countries.byCountryIsoCode(countryIsoCode) == null) {
            return null
        }
        return try {
            client.priceInfo(pluginId, countryIsoCode)
        } catch (e: ClientRequestException) {
            if (e.response.status != HttpStatusCode.NotFound) {
                throw e
            }
            null
        }
    }

    companion object {
        fun create(client: MarketplaceClient, pluginId: PluginId, countries: Countries): PluginPricing {
            return PluginPricing(client, pluginId, countries)
        }
    }
}
