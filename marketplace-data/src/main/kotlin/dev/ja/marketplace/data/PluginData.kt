/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.trackers.ContinuityDiscountTracker
import dev.ja.marketplace.services.Countries

data class PluginData(
    // JetBrains Services
    val countries: Countries,

    // marketplace data
    val pluginId: PluginId,
    val pluginSummary: PluginInfoSummary,
    val pluginInfo: PluginInfo,
    val pluginRating: PluginRating,
    val totalDownloads: Long,
    val downloadsMonthly: List<MonthlyDownload>,
    val downloadsDaily: List<DailyDownload>,
    val downloadsProduct: List<ProductDownload>,

    // info for paid or freemium plugin/s
    val sales: List<PluginSale>?,
    val licenses: List<LicenseInfo>?,
    val trials: List<PluginTrial>?,
    val marketplacePluginInfo: MarketplacePluginInfo?,
    val pluginPricing: PluginPricing?,
) {
    val continuityDiscountTracker: ContinuityDiscountTracker?

    init {
        when (licenses) {
            null -> {
                continuityDiscountTracker = null
            }

            else -> {
                continuityDiscountTracker = ContinuityDiscountTracker()
                licenses.forEach(continuityDiscountTracker::process)
            }
        }
    }
}