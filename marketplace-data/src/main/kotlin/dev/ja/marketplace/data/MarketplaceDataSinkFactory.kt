/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.MarketplaceClient

interface MarketplaceDataSinkFactory {
    fun createTableSink(client: MarketplaceClient, maxTableRows: Int?): MarketplaceDataSink
}