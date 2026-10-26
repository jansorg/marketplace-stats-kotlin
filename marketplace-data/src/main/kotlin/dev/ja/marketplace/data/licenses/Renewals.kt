/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDay

/**
 * The rule for renewals of subscriptions, which the churn, the renewal rates and the license cohorts share:
 * a subscription is renewed by a later subscription of the same license, which starts after at most [graceDays] days
 * without a license, see [Marketplace.LICENSE_RENEWAL_GRACE_DAYS].
 */
object Renewals {
    /**
     * @return The last day, on which a renewal of a subscription, which ends at [end], can start.
     */
    fun deadline(end: YearMonthDay, graceDays: Int = Marketplace.LICENSE_RENEWAL_GRACE_DAYS): YearMonthDay {
        return end.add(0, 0, graceDays + 1)
    }
}
