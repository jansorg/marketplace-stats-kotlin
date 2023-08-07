/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.DownloadCountType.Downloads
import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.data.LicenseInfo
import dev.ja.marketplace.data.PluginData
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicReference

class PluginDataLoader(private val pluginId: PluginId, private val client: MarketplaceClient) {
    private val cachedData = AtomicReference<PluginData>()

    suspend fun loadCached(): PluginData {
        return when (val cached = cachedData.get()) {
            null -> {
                val data = load()
                cachedData.set(data)
                data
            }

            else -> cached
        }
    }

    private suspend fun load(): PluginData {
        return coroutineScope {
            val pluginInfo = async { client.pluginInfo(pluginId) }
            val pluginRating = async { client.pluginRating(pluginId) }
            val sales = async { client.salesInfo(pluginId) }
            val licenseInfo = async { LicenseInfo.create(sales.await()) }
            val trials = async { client.trialsInfo(pluginId) }
            val downloadsTotal = async { client.downloadsTotal(pluginId) }
            val downloadsMonthly = async { client.downloadsMonthly(pluginId, Downloads) }
            val downloadsDaily = async { client.downloadsDaily(pluginId, Downloads) }
            val downloadsProduct = async { client.downloadsByProduct(pluginId, Downloads) }

            PluginData(
                pluginId,
                pluginInfo.await(),
                pluginRating.await(),
                sales.await(),
                licenseInfo.await(),
                trials.await(),
                downloadsTotal.await(),
                downloadsMonthly.await(),
                downloadsDaily.await(),
                downloadsProduct.await(),
            )
        }
    }
}