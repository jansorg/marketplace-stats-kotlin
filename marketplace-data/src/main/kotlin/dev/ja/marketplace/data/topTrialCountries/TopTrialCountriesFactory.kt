/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.topTrialCountries

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.data.DataTable
import dev.ja.marketplace.data.MarketplaceDataTableFactory

class TopTrialCountriesFactory(
    private val maxCountries: Int?,
    private val smallSpace: Boolean,
    private val showEmptyCountry: Boolean
) : MarketplaceDataTableFactory {
    override fun createTable(client: MarketplaceClient, maxTableRows: Int?): DataTable {
        return TopTrialCountriesTable(maxCountries, smallSpace, showEmptyCountry)
    }
}