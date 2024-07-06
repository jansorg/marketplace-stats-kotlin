/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.LicenseId
import dev.ja.marketplace.data.LicenseInfo
import java.util.*

/**
 * Tracks the current continuity discount of licenses and can provide the continuity discount
 */
class ContinuityDiscountTracker {
    private val newSalesValidity = mutableMapOf<LicenseId, TreeSet<LicenseInfo>>()

    fun process(license: LicenseInfo) {
        if (license.isNewLicense) {
            val sales = newSalesValidity.computeIfAbsent(license.id) { TreeSet() }
            sales += license
        }
    }

    fun nextContinuityFactor(licenseId: LicenseId, atDate: YearMonthDay): Double {
        val latestNewSale = newSalesValidity[licenseId]?.lastOrNull { it.validity.end < atDate } ?: return 1.0
        val months = latestNewSale.validity.start.monthsUntil(atDate)
        return when {
            months >= 24 -> 0.6 // 40% in the 3rd year or later
            months >= 12 -> 0.8 // 20% in the 2nd year or later
            else -> 1.0 // 0%
        }
    }
}