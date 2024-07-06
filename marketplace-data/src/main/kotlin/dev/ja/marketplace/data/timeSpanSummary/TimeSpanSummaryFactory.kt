/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.timeSpanSummary

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.data.MarketplaceDataSink
import dev.ja.marketplace.data.MarketplaceDataSinkFactory

class TimeSpanSummaryFactory(private val maxDays: Int, private val title: String) : MarketplaceDataSinkFactory {
    override fun createTableSink(client: MarketplaceClient, maxTableRows: Int?): MarketplaceDataSink {
        return TimeSpanSummaryTable(maxDays, title)
    }
}