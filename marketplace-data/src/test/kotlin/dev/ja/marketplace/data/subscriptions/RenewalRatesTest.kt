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

class RenewalRatesTest {
    @Test
    fun renewalsAreGroupedByYearPeriodAndPreviousRenewals() {
        val annual = LicensePeriod.Annual
        val monthly = LicensePeriod.Monthly
        val licenses = licenses(
            // renewed once, then not renewed
            sale(customer(1), "a", YearMonthDay(2023, 1, 1), 100, annual),
            sale(customer(1), "a", YearMonthDay(2024, 1, 1), 100, annual, PluginSaleItemType.Renew),
            // not renewed within the grace period
            sale(customer(2), "b", YearMonthDay(2025, 3, 1), 100, annual),
            // ended recently, can still be renewed
            sale(customer(3), "c", YearMonthDay(2025, 4, 1), 100, annual),
            // renewed late, but within the grace period, then pending
            sale(customer(4), "d", YearMonthDay(2026, 1, 1), 10, monthly),
            sale(customer(4), "d", YearMonthDay(2026, 2, 15), 10, monthly, PluginSaleItemType.Renew),
        )

        val rates = RenewalRates.of(licenses, today = YearMonthDay(2026, 6, 1))
        val summary = rates.map { "${it.year} ${it.licensePeriod} ${it.previousRenewals}: ${it.renewed}/${it.due} ${it.isIncomplete}" }
        assertEquals(
            listOf(
                "2023 Annual 0: 1/1 false",
                "2024 Annual 1: 0/1 false",
                "2026 Monthly 0: 1/1 false",
                "2026 Monthly 1: 0/0 true",
                "2026 Annual 0: 0/1 true",
            ).sorted(),
            summary.sorted(),
        )

        val monthlyRates = RenewalRates.of(licenses, today = YearMonthDay(2026, 6, 1), byMonth = true)
            .filter { it.licensePeriod == monthly }
            .map { "${it.year}-${it.month} ${it.previousRenewals}: ${it.renewed}/${it.due} ${it.isIncomplete}" }
        assertEquals(listOf("2026-1 0: 1/1 false", "2026-3 1: 0/0 true"), monthlyRates)
    }

    @Test
    fun renewalAfterAtMostTheGracePeriodWithoutALicense() {
        val annual = LicensePeriod.Annual
        // the subscriptions end at 2024-12-31, the renewals start after 90 and after 91 days without a license
        val licenses = licenses(
            sale(customer(1), "a", YearMonthDay(2024, 1, 1), 100, annual),
            sale(customer(1), "a", YearMonthDay(2025, 4, 1), 100, annual, PluginSaleItemType.Renew),
            sale(customer(2), "b", YearMonthDay(2024, 1, 1), 100, annual),
            sale(customer(2), "b", YearMonthDay(2025, 4, 2), 100, annual, PluginSaleItemType.Renew),
        )
        val rate = RenewalRates.of(licenses, today = YearMonthDay(2026, 10, 1)).single { it.year == 2024 }
        assertEquals(1, rate.renewed)
        assertEquals(2, rate.due)
    }

    @Test
    fun freeSubscriptionsAreNoRenewals() {
        val annual = LicensePeriod.Annual
        val licenses = licenses(
            sale(customer(1), "a", YearMonthDay(2023, 1, 1), 100, annual),
            sale(customer(1), "a", YearMonthDay(2024, 1, 1), 0, annual, PluginSaleItemType.Renew),
        )
        val rate = RenewalRates.of(licenses, today = YearMonthDay(2026, 10, 1)).single()
        assertEquals("2023 0: 0/1", "${rate.year} ${rate.previousRenewals}: ${rate.renewed}/${rate.due}")
    }

    @Test
    fun newPurchaseOfTheSameLicenseIsNoRenewal() {
        val annual = LicensePeriod.Annual
        val licenses = licenses(
            sale(customer(1), "a", YearMonthDay(2023, 1, 1), 100, annual),
            sale(customer(1), "a", YearMonthDay(2024, 1, 1), 100, annual, PluginSaleItemType.New),
        )
        val rate = RenewalRates.of(licenses, today = YearMonthDay(2026, 10, 1)).single { it.year == 2023 }
        assertEquals(0, rate.renewed)
        assertEquals(1, rate.due)
    }
}
