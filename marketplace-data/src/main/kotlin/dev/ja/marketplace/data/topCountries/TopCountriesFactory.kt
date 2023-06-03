/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.topCountries

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.data.MarketplaceDataSink
import dev.ja.marketplace.data.MarketplaceDataSinkFactory

class TopCountriesFactory(private val maxCountries: Int = 10) : MarketplaceDataSinkFactory {
    override fun createTableSink(client: MarketplaceClient): MarketplaceDataSink {
        return TopCountriesTable(maxCountries)
    }
}