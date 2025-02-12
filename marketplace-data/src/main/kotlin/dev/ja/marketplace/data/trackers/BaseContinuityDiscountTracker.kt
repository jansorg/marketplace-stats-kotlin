/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.ContinuityDiscount
import java.util.*

interface ContinuityDiscountTracker {
    fun process(license: LicenseInfo)
    fun nextContinuity(licenseId: LicenseId, atDate: YearMonthDay): ContinuityDiscount
}

/**
 * Tracks the current continuity discount of licenses and can provide the continuity discount
 */
class BaseContinuityDiscountTracker : ContinuityDiscountTracker {
    private val newSalesValidity = mutableMapOf<LicenseId, TreeSet<LicenseInfo>>()

    override fun process(license: LicenseInfo) {
        if (license.isNewLicense && license.isSubscriptionLicense) {
            val sales = newSalesValidity.computeIfAbsent(license.id) { TreeSet() }
            sales += license
        }
    }

    override fun nextContinuity(licenseId: LicenseId, atDate: YearMonthDay): ContinuityDiscount {
        val latestNewSale = newSalesValidity[licenseId]?.lastOrNull { it.validity!!.end < atDate } ?: return ContinuityDiscount.FirstYear
        val months = latestNewSale.validity!!.start monthsUntil atDate
        return when {
            months >= 24 -> ContinuityDiscount.ThirdYear
            months >= 12 -> ContinuityDiscount.SecondYear
            else -> ContinuityDiscount.FirstYear
        }
    }
}
