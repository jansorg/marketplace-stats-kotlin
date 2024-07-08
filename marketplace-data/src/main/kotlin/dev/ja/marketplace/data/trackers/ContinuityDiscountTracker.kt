/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.ContinuityDiscount
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

    fun nextContinuity(licenseId: LicenseId, atDate: YearMonthDay): ContinuityDiscount {
        val latestNewSale = newSalesValidity[licenseId]?.lastOrNull { it.validity.end < atDate } ?: return ContinuityDiscount.FirstYear
        val months = latestNewSale.validity.start.monthsUntil(atDate)
        return when {
            months >= 24 -> ContinuityDiscount.ThirdYear
            months >= 12 -> ContinuityDiscount.SecondYear
            else -> ContinuityDiscount.FirstYear
        }
    }
}