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
import dev.ja.marketplace.data.trackers.BaseContinuityDiscountTracker
import dev.ja.marketplace.exchangeRate.ExchangeRates
import dev.ja.marketplace.services.Countries
import kotlinx.coroutines.*

class PluginDataLoader(
    val client: MarketplaceClient,
    val plugin: PluginInfoSummary,
    val countries: Countries,
    val exchangeRates: ExchangeRates,
) {
    suspend fun load(): PluginData {
        return coroutineScope {
            val pluginInfo = async { client.pluginInfo(plugin.id) }
            val pluginRating = async { client.pluginRating(plugin.id) }
            val downloadsTotal = async { client.downloadsTotal(plugin.id) }
            val downloadsMonthly = async { client.downloadsMonthly(plugin.id, Downloads) }
            val downloadsDaily = async { client.downloadsDaily(plugin.id, Downloads) }

            val salesAndLicenses = loadAsyncIfPaid { client.licenseInfo(plugin.id) }
            val trials = loadAsyncIfPaid { client.trialsInfo(plugin.id) }

            val continuityDiscountTracker = when (salesAndLicenses) {
                null -> null
                else -> async(Dispatchers.IO) {
                    val tracker = BaseContinuityDiscountTracker()
                    salesAndLicenses.await().licenses.forEach(tracker::process)
                    tracker
                }
            }

            val data = PluginData(
                countries,
                exchangeRates,
                plugin,
                pluginInfo,
                pluginRating,
                downloadsTotal,
                downloadsMonthly,
                downloadsDaily,
                PluginPricing.create(client, plugin.id, countries),
                salesAndLicenses,
                trials,
                continuityDiscountTracker,
            )

            data
        }
    }

    private fun <T> CoroutineScope.loadAsyncIfPaid(block: suspend () -> T): Deferred<T>? {
        if (!plugin.isPaidOrFreemium) {
            return null
        }

        return async {
            block()
        }
    }
}