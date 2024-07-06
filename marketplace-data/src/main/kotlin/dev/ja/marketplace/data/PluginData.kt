/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.*

data class PluginData(
    val pluginId: PluginId,
    val pluginSummary: PluginInfoSummary,
    val pluginInfo: PluginInfo,
    val marketplacePluginInfo: MarketplacePluginInfo,
    val pluginRating: PluginRating,
    val totalDownloads: Long,
    val downloadsMonthly: List<MonthlyDownload>,
    val downloadsDaily: List<DailyDownload>,
    val downloadsProduct: List<ProductDownload>,
    // info for paid or freemium plugin/s
    val sales: List<PluginSale>?,
    val licenses: List<LicenseInfo>?,
    val trials: List<PluginTrial>?,
)

