/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.PluginSale

interface MarketplaceDataSink {
    fun init(data: PluginData) {}

    fun process(sale: PluginSale) {}

    fun process(licenseInfo: LicenseInfo)
}
