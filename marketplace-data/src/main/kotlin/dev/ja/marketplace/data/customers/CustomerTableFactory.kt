/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customers

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.MarketplaceDataSink
import dev.ja.marketplace.data.MarketplaceDataSinkFactory

class CustomerTableFactory : MarketplaceDataSinkFactory {
    override fun createTableSink(client: MarketplaceClient): MarketplaceDataSink {
        return CustomerTable()
    }
}

class ActiveCustomerTableFactory : MarketplaceDataSinkFactory {
    override fun createTableSink(client: MarketplaceClient): MarketplaceDataSink {
        return CustomerTable({ it.activeLicenses.isNotEmpty() })
    }
}

class ChurnedCustomerTableFactory : MarketplaceDataSinkFactory {
    override fun createTableSink(client: MarketplaceClient): MarketplaceDataSink {
        return CustomerTable({ YearMonthDay.now() > it.latestLicenseEnd!! }, true)
    }
}