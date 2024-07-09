/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.daySummary

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.DataTable
import dev.ja.marketplace.data.MarketplaceDataTableFactory

class DaySummaryFactory(val offsetDays: Int, val title: String) : MarketplaceDataTableFactory {
    override fun createTable(client: MarketplaceClient, maxTableRows: Int?): DataTable {
        return DaySummaryTable(YearMonthDay.now().add(0, 0, offsetDays), title)
    }
}
