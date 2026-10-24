/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.marketplace.client.YearMonthDay

/**
 * The status of a license with a subscription. It depends on the latest purchase of all customers, because a license
 * can be renewed by another customer, e.g. after it was transferred to an organization.
 */
enum class LicenseStatus {
    Active,
    Churned;

    companion object {
        /**
         * @param latestEnd The end of the latest purchase of the license, of all customers.
         */
        fun of(latestEnd: YearMonthDay, today: YearMonthDay): LicenseStatus {
            return if (latestEnd < today) Churned else Active
        }
    }
}
