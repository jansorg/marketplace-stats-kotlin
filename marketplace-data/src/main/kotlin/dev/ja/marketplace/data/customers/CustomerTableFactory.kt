/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customers

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.DataTable
import dev.ja.marketplace.data.MarketplaceDataTableFactory

class CustomerTableFactory : MarketplaceDataTableFactory {
    override fun createTable(client: MarketplaceClient, maxTableRows: Int?): DataTable {
        return CustomerTable()
    }
}

class ActiveCustomerTableFactory : MarketplaceDataTableFactory {
    override fun createTable(client: MarketplaceClient, maxTableRows: Int?): DataTable {
        return CustomerTable({ it.activeLicenses.isNotEmpty() })
    }
}

class ChurnedCustomerTableFactory : MarketplaceDataTableFactory {
    override fun createTable(client: MarketplaceClient, maxTableRows: Int?): DataTable {
        return CustomerTable({ YearMonthDay.now() > it.latestLicenseEnd!! }, true)
    }
}