/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.model.CustomerInfo
import dev.ja.marketplace.client.YearMonthDay

class CustomerChurnProcessor(
    previouslyActiveMarkerDate: YearMonthDay,
    currentlyActiveMarkerDate: YearMonthDay
) : MarketplaceIntChurnProcessor<CustomerInfo>(previouslyActiveMarkerDate, currentlyActiveMarkerDate) {
    override fun getId(value: CustomerInfo): Int {
        return value.code
    }
}