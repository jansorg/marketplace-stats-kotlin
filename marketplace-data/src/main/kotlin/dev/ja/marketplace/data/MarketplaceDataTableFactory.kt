/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.MarketplaceClient

interface MarketplaceDataTableFactory {
    fun createTable(client: MarketplaceClient, maxTableRows: Int?): DataTable
}