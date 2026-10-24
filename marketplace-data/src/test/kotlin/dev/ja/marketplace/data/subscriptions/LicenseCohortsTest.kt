/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.customer
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.licenses
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.sale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LicenseCohortsTest {
    private val annual = LicensePeriod.Annual
    private val renew = PluginSaleItemType.Renew

    @Test
    fun licensesAreGroupedByMonthOfTheFirstPaidSubscription() {
        val licenses = licenses(
            // renewed twice
            sale(customer(1), "a", YearMonthDay(2023, 1, 1), 100, annual),
            sale(customer(1), "a", YearMonthDay(2024, 1, 1), 100, annual, renew),
            sale(customer(1), "a", YearMonthDay(2025, 1, 1), 100, annual, renew),
            // not renewed
            sale(customer(2), "b", YearMonthDay(2023, 1, 20), 100, annual),
            // renewed late, within the grace period, then not renewed
            sale(customer(3), "c", YearMonthDay(2023, 1, 31), 100, annual),
            sale(customer(3), "c", YearMonthDay(2024, 3, 15), 100, annual, renew),
            // another cohort, the first renewal is pending
            sale(customer(4), "d", YearMonthDay(2025, 4, 1), 100, annual),
            // free licenses don't start a cohort
            sale(customer(5), "e", YearMonthDay(2023, 1, 5), 0, annual),
        )

        val cohorts = LicenseCohorts.of(licenses, maxRenewals = 5, today = YearMonthDay(2026, 6, 1))
        assertEquals(listOf(YearMonthDay(2023, 1, 1), YearMonthDay(2025, 4, 1)), cohorts.map { it.month })

        val january = cohorts[0]
        assertEquals(3, january.licenses)
        assertEquals(
            listOf("1: 2/3 pending 0 decided 3", "2: 1/2 pending 0 decided 3", "3: 0/1 pending 0 decided 3"),
            january.renewals.map { "${it.renewal}: ${it.renewed}/${it.due} pending ${it.pending} decided ${it.decided}" },
        )

        val april = cohorts[1]
        assertEquals(1, april.licenses)
        assertEquals(listOf("1: 0/0 pending 1 decided 0"), april.renewals.map { "${it.renewal}: ${it.renewed}/${it.due} pending ${it.pending} decided ${it.decided}" })
        assertEquals(true, april.renewals.single().isIncomplete)
    }

    @Test
    fun renewalsAreLimited() {
        val licenses = licenses(
            sale(customer(1), "a", YearMonthDay(2025, 1, 1), 10),
            sale(customer(1), "a", YearMonthDay(2025, 2, 1), 10, type = renew),
            sale(customer(1), "a", YearMonthDay(2025, 3, 1), 10, type = renew),
        )
        val cohort = LicenseCohorts.of(licenses, maxRenewals = 1, today = YearMonthDay(2026, 6, 1)).single()
        assertEquals(LicensePeriod.Monthly, cohort.licensePeriod)
        assertEquals(1, cohort.renewals.size)
    }
}
