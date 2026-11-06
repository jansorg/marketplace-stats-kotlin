/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customers

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.data.DataTable
import dev.ja.marketplace.data.MarketplaceDataTableFactory
import dev.ja.marketplace.data.licenses.LicenseStatus

/**
 * @param status Only customers with this status, all customers if it's `null`.
 */
class CustomerTableFactory(private val status: LicenseStatus? = null) : MarketplaceDataTableFactory {
    override fun createTable(client: MarketplaceClient, maxTableRows: Int?): DataTable {
        return CustomerTable(maxTableRows = maxTableRows, statusFilter = status)
    }
}
