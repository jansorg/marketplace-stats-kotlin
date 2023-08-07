/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.*

data class PluginData(
    val pluginId: PluginId,
    val pluginInfo: PluginInfo,
    val pluginRating: PluginRating,
    val sales: List<PluginSale>,
    val licenses: List<LicenseInfo>,
    val trials: List<PluginTrial>,
    val totalDownloads: Long,
    val downloadsMonthly: List<MonthlyDownload>,
    val downloadsDaily: List<DailyDownload>,
    val downloadsProduct: List<ProductDownload>,
)

