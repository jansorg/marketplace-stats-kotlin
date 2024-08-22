/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.PluginTagName
import dev.ja.marketplace.client.PluginXmlId

interface PluginInfoExtended : PluginInfoBase {
    val xmlId: PluginXmlId
    val downloads: Int
    val tagNames: List<PluginTagName>
    val pricingModel: PluginPricingModel
    val previewText: String?
    val iconUrlPath: String?

    val isPaidOrFreemium: Boolean
        get() {
            return pricingModel == PluginPricingModel.Paid || pricingModel == PluginPricingModel.Freemium
        }
}