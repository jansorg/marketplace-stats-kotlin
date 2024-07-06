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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class PluginDataLoader(val plugin: PluginInfoSummary, val client: MarketplaceClient) {
    suspend fun load(): PluginData {
        return coroutineScope {
            val pluginInfo = async { client.pluginInfo(plugin.id) }
            val marketplacePluginInfo = async { client.marketplacePluginInfo(plugin.id) }
            val pluginRating = async { client.pluginRating(plugin.id) }
            val downloadsTotal = async { client.downloadsTotal(plugin.id) }
            val downloadsMonthly = async { client.downloadsMonthly(plugin.id, Downloads) }
            val downloadsDaily = async { client.downloadsDaily(plugin.id, Downloads) }
            val downloadsProduct = async { client.downloadsByProduct(plugin.id, Downloads) }

            val sales = when {
                plugin.isPaidOrFreemium -> async { client.salesInfo(plugin.id) }
                else -> null
            }
            val licenseInfo = when {
                plugin.isPaidOrFreemium && sales != null -> async { LicenseInfo.create(sales.await()) }
                else -> null
            }
            val trials = when {
                plugin.isPaidOrFreemium -> async { client.trialsInfo(plugin.id) }
                else -> null
            }

            PluginData(
                plugin.id,
                plugin,
                pluginInfo.await(),
                marketplacePluginInfo.await(),
                pluginRating.await(),
                downloadsTotal.await(),
                downloadsMonthly.await(),
                downloadsDaily.await(),
                downloadsProduct.await(),
                sales?.await(),
                licenseInfo?.await(),
                trials?.await(),
            )
        }
    }
}