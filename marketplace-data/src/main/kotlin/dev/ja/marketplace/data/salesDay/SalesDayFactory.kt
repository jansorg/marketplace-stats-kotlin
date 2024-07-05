/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.salesDay

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.MarketplaceDataSink
import dev.ja.marketplace.data.MarketplaceDataSinkFactory

class SalesDayFactory(val offsetDays: Int, val title: String) : MarketplaceDataSinkFactory {
    override fun createTableSink(client: MarketplaceClient, maxTableRows: Int?): MarketplaceDataSink {
        return SalesDayTable(YearMonthDay.now().add(0, 0, offsetDays), title)
    }
}
