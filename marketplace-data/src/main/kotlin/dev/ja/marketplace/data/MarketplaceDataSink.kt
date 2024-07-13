/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.PluginSale

interface MarketplaceDataSink {
    suspend fun init(data: PluginData)

    suspend fun process(sale: PluginSale) {}

    suspend fun process(licenseInfo: LicenseInfo)
}
