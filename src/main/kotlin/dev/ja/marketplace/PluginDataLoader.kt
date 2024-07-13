/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.DownloadCountType.Downloads
import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.PluginInfoSummary
import dev.ja.marketplace.data.PluginData
import dev.ja.marketplace.data.PluginPricing
import dev.ja.marketplace.exchangeRate.ExchangeRates
import dev.ja.marketplace.services.Countries
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class PluginDataLoader(
    val client: MarketplaceClient,
    val plugin: PluginInfoSummary,
    val countries: Countries,
    val exchangeRates: ExchangeRates,
) {
    suspend fun load(): PluginData {
        return coroutineScope {
            val pluginInfo = async(Dispatchers.IO) { client.pluginInfo(plugin.id) }
            val pluginRating = async(Dispatchers.IO) { client.pluginRating(plugin.id) }
            val downloadsTotal = async(Dispatchers.IO) { client.downloadsTotal(plugin.id) }
            val downloadsMonthly = async(Dispatchers.IO) { client.downloadsMonthly(plugin.id, Downloads) }
            val downloadsDaily = async(Dispatchers.IO) { client.downloadsDaily(plugin.id, Downloads) }
            val downloadsProduct = async(Dispatchers.IO) { client.downloadsByProduct(plugin.id, Downloads) }

            val salesAndLicenses = loadAsyncIfPaid { client.licenseInfo(plugin.id) }
            val trials = loadAsyncIfPaid { client.trialsInfo(plugin.id) }
            val marketplacePluginInfo = loadAsyncIfPaid { client.marketplacePluginInfo(plugin.id) }
            val pricingInfo = loadAsyncIfPaid { PluginPricing.create(client, plugin.id, countries) }

            PluginData(
                countries,
                exchangeRates,
                plugin.id,
                plugin,
                pluginInfo.await(),
                pluginRating.await(),
                downloadsTotal.await(),
                downloadsMonthly.await(),
                downloadsDaily.await(),
                downloadsProduct.await(),
                salesAndLicenses?.await()?.sales,
                salesAndLicenses?.await()?.licenses,
                trials?.await(),
                marketplacePluginInfo?.await(),
                pricingInfo?.await(),
            )
        }
    }

    private suspend fun <T> loadAsyncIfPaid(block: suspend () -> T): Deferred<T>? {
        if (!plugin.isPaidOrFreemium) {
            return null
        }

        return coroutineScope {
            async(Dispatchers.IO) {
                block()
            }
        }
    }
}