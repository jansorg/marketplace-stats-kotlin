/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trialsToday

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.data.MarketplaceDataSink
import dev.ja.marketplace.data.MarketplaceDataSinkFactory

class TrialsTodayFactory : MarketplaceDataSinkFactory {
    override fun createTableSink(client: MarketplaceClient): MarketplaceDataSink {
        return TrialsTodayTable()
    }
}