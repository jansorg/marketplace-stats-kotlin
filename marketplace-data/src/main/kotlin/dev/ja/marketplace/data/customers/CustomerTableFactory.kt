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
    private val now = YearMonthDay.now()

    override fun createTableSink(client: MarketplaceClient): MarketplaceDataSink {
        return CustomerTable({ licenseInfo -> now in licenseInfo.validity })
    }
}

class ChurnedCustomerTableFactory : MarketplaceDataSinkFactory {
    private val now = YearMonthDay.now()

    override fun createTableSink(client: MarketplaceClient): MarketplaceDataSink {
        return CustomerTable({ true }, { _, latestValid -> now > latestValid }, false)
    }
}