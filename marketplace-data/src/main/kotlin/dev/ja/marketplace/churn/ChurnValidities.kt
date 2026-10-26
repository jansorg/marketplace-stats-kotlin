/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.data.licenses.Renewals
import java.util.IdentityHashMap

/**
 * Validities of licenses for the calculation of churn.
 *
 * A license, which is renewed within [Marketplace.LICENSE_RENEWAL_GRACE_DAYS] days after it expired,
 * is treated as valid until the start of the renewal. A license renewed later is churned when it expires.
 */
class ChurnValidities(licenses: Iterable<LicenseInfo>, graceDays: Int = Marketplace.LICENSE_RENEWAL_GRACE_DAYS) {
    private val extendedValidities = IdentityHashMap<LicenseInfo, YearMonthDayRange>()

    init {
        licenses.filter { it.validity != null }
            .groupBy { it.id }
            .values
            .forEach { sameLicense ->
                val sorted = sameLicense.sortedBy { it.validity!!.start }
                for ((license, next) in sorted.zipWithNext()) {
                    val validity = license.validity!!
                    val nextStart = next.validity!!.start
                    // a renewal, which starts directly after the end, doesn't need an extension
                    val hasGap = nextStart > validity.end.add(0, 0, 1)
                    if (hasGap && Renewals.isRenewal(license, next, graceDays)) {
                        extendedValidities[license] = validity.copy(end = nextStart.add(0, 0, -1))
                    }
                }
            }
    }

    /**
     * @return The validity of the license used for churn, or `null` if the license has no validity, e.g. a perpetual license.
     */
    fun validity(license: LicenseInfo): YearMonthDayRange? {
        return extendedValidities[license] ?: license.validity
    }
}
