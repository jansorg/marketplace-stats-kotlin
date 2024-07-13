/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.DownloadCountType.Downloads
import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.PluginInfoSummary
import dev.ja.marketplace.data.LicenseInfo
import dev.ja.marketplace.data.PluginData
import dev.ja.marketplace.data.PluginPricing
import dev.ja.marketplace.exchangeRate.ExchangeRates
import dev.ja.marketplace.services.Countries
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

            val sales = when {
                plugin.isPaidOrFreemium -> async(Dispatchers.IO) { client.salesInfo(plugin.id) }
                else -> null
            }
            val licenseInfos = when {
                plugin.isPaidOrFreemium && sales != null -> async(Dispatchers.IO) { LicenseInfo.create(sales.await()) }
                else -> null
            }
            val trials = when {
                plugin.isPaidOrFreemium -> async(Dispatchers.IO) { client.trialsInfo(plugin.id) }
                else -> null
            }
            val marketplacePluginInfo = when {
                plugin.isPaidOrFreemium -> async(Dispatchers.IO) { client.marketplacePluginInfo(plugin.id) }
                else -> null
            }
            val pricingInfo = when {
                plugin.isPaidOrFreemium -> async(Dispatchers.IO) { PluginPricing.create(client, plugin.id, countries) }
                else -> null
            }

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
                sales?.await(),
                licenseInfos?.await(),
                trials?.await(),
                marketplacePluginInfo?.await(),
                pricingInfo?.await(),
            )
        }
    }
}