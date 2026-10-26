/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDay

/**
 * The rule for renewals of subscriptions, which the churn, the renewal rates and the license cohorts share:
 * a subscription is renewed by a later renewal sale of the same license, which starts after at most `graceDays` days
 * without a license, see [Marketplace.LICENSE_RENEWAL_GRACE_DAYS].
 */
object Renewals {
    /**
     * @return If [next] is a renewal of [subscription]: a renewal sale of the same license, which starts after the start
     *  of [subscription] and at most at the [deadline]. A new purchase of the same license isn't a renewal.
     */
    fun isRenewal(subscription: LicenseInfo, next: LicenseInfo, graceDays: Int = Marketplace.LICENSE_RENEWAL_GRACE_DAYS): Boolean {
        val validity = subscription.validity ?: return false
        val nextStart = next.validity?.start ?: return false
        return next.id == subscription.id && next.isRenewalLicense && nextStart > validity.start && nextStart <= deadline(validity.end, graceDays)
    }

    /**
     * @return The last day, on which a renewal of a subscription, which ends at [end], can start.
     */
    fun deadline(end: YearMonthDay, graceDays: Int = Marketplace.LICENSE_RENEWAL_GRACE_DAYS): YearMonthDay {
        return end.add(0, 0, graceDays + 1)
    }
}
