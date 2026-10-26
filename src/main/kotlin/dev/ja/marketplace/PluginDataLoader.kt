/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.model.DownloadCountType.Downloads
import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.model.PluginInfoSummary
import dev.ja.marketplace.data.PluginData
import dev.ja.marketplace.data.PluginPricing
import dev.ja.marketplace.exchangeRate.ExchangeRates
import dev.ja.marketplace.services.Countries
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(PluginDataLoader::class.java)

class PluginDataLoader(
    val client: MarketplaceClient,
    val plugin: PluginInfoSummary,
    val countries: Countries,
    val exchangeRates: ExchangeRates,
    val ideReleases: IdeReleaseProvider,
    /** The other paid and freemium plugins of the account, the report pages link to them. */
    val otherPaidPlugins: List<PluginInfoSummary> = emptyList(),
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
            val marketplacePluginInfo = loadAsyncIfPaid {
                // optional data, an error must not fail the other data
                try {
                    client.marketplacePluginInfo(plugin.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Unable to load the marketplace info of plugin ${plugin.id}", e)
                    null
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
                marketplacePluginInfo,
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