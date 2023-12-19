/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.downloads

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.data.MarketplaceDataSink
import dev.ja.marketplace.data.MarketplaceDataSinkFactory

class MonthlyDownloadsFactory : MarketplaceDataSinkFactory {
    override fun createTableSink(client: MarketplaceClient, maxTableRows: Int?): MarketplaceDataSink {
        return DownloadsTable()
    }
}