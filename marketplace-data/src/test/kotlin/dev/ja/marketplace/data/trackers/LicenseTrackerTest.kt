/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.customer
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.licenses
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.sale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LicenseTrackerTest {
    @Test
    fun licensesAreCountedOncePerSegment() {
        val licenses = licenses(
            // two sales of the same license, which are both valid at the end of the month
            sale(customer(1), "a", YearMonthDay(2025, 1, 1), 100, LicensePeriod.Annual),
            sale(customer(1), "a", YearMonthDay(2025, 9, 1), 10, LicensePeriod.Monthly, PluginSaleItemType.Renew),
            sale(customer(2), "b", YearMonthDay(2025, 9, 1), 10, LicensePeriod.Monthly),
            sale(customer(3), "c", YearMonthDay(2025, 9, 1), 0, LicensePeriod.Monthly),
            // expired before the end of the month
            sale(customer(4), "d", YearMonthDay(2025, 8, 1), 10, LicensePeriod.Monthly),
        )

        val tracker = LicenseTracker<String>(YearMonthDayRange.ofMonth(2025, 9))
        for (license in licenses) {
            tracker.add(if (license.sale.customer.code == 1) "first" else "other", license)
        }

        assertEquals(3, tracker.totalLicenseCount)
        assertEquals(2, tracker.paidLicensesCount)
        assertEquals(1, tracker.segmentLicenseCount("first"))
        assertEquals(2, tracker.segmentLicenseCount("other"))
        assertEquals(0, tracker.segmentLicenseCount("unknown"))
    }
}
