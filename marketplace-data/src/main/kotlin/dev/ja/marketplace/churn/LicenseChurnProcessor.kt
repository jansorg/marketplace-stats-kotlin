/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.LicenseInfo

class LicenseChurnProcessor(
    previouslyActiveMarkerDate: YearMonthDay,
    currentlyActiveMarkerDate: YearMonthDay
) : MarketplaceStringChurnProcessor<LicenseInfo>(previouslyActiveMarkerDate, currentlyActiveMarkerDate) {
    override fun getId(value: LicenseInfo): String {
        return value.id
    }
}