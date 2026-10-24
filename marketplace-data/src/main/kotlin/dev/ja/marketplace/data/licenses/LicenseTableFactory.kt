/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.data.DataTable
import dev.ja.marketplace.data.MarketplaceDataTableFactory

/**
 * @param status Only licenses with this status, with their latest purchase. All purchases of all licenses if it's `null`.
 */
class LicenseTableFactory(private val status: LicenseStatus? = null) : MarketplaceDataTableFactory {
    override fun createTable(client: MarketplaceClient, maxTableRows: Int?): DataTable {
        return LicenseTable(maxTableRows, statusFilter = status, showOnlyLatestLicenseInfo = status != null)
    }
}