/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.funnel

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.data.DataTable
import dev.ja.marketplace.data.MarketplaceDataTableFactory

class FunnelTableFactory :MarketplaceDataTableFactory {
    override fun createTable(client: MarketplaceClient, maxTableRows: Int?): DataTable {
        return FunnelTable()
    }
}