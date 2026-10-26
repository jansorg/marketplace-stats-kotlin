/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.model.LicensePeriod
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The rules of the recurring revenue, shared by the tables, the values per day of the charts and the subscription
 * metrics:
 * - Only paid subscriptions count, see [isRecurring].
 * - Of the sales of a license, which are valid at a day, the sale with the latest end counts. Of sales with the same end,
 *   the earlier sale counts, independent of the order of the sales.
 * - The MRR is the amount of a monthly sale or 1/12 of the amount of an annual sale, the ARR the amount of an annual sale
 *   or 12 times the amount of a monthly sale.
 */
object RecurringRevenueSales {
    private val annualToMonthly = BigDecimal.ONE.divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP)
    private val monthlyToAnnual = BigDecimal.valueOf(12)

    /** @return If the sale counts for the recurring revenue: a paid subscription. */
    fun isRecurring(license: LicenseInfo): Boolean {
        return license.validity != null && license.isPaidLicense && license.isSubscriptionLicense
    }

    /** @return The factor of the amount of a sale for the MRR. */
    fun monthlyFactor(licensePeriod: LicensePeriod): BigDecimal {
        return if (licensePeriod == LicensePeriod.Annual) annualToMonthly else BigDecimal.ONE
    }

    /** @return The factor of the amount of a sale for the ARR. */
    fun annualFactor(licensePeriod: LicensePeriod): BigDecimal {
        return if (licensePeriod == LicensePeriod.Monthly) monthlyToAnnual else BigDecimal.ONE
    }

    private val comparator: Comparator<LicenseInfo> = compareBy<LicenseInfo> { it.validity!!.end }
        .thenByDescending { it.sale.date }
        .thenByDescending { it.sale.ref }

    /** @return The sale of [a] and [b], which counts for the recurring revenue. */
    fun preferred(a: LicenseInfo, b: LicenseInfo): LicenseInfo = if (comparator.compare(b, a) > 0) b else a

    /** @return The sale, which counts for the recurring revenue, `null` without sales. */
    fun latest(sales: Iterable<LicenseInfo>): LicenseInfo? = sales.maxWithOrNull(comparator)
}
