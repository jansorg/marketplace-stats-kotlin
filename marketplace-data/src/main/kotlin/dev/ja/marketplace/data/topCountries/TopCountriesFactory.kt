/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.topCountries

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.data.DataTable
import dev.ja.marketplace.data.MarketplaceDataTableFactory

class TopCountriesFactory(
    private val smallSpace: Boolean = true,
    private val maxCountries: Int? = 10,
    private val showTrials: Boolean = false
) : MarketplaceDataTableFactory {
    override fun createTable(client: MarketplaceClient, maxTableRows: Int?): DataTable {
        return TopCountriesTable(smallSpace, maxCountries, showTrials)
    }
}