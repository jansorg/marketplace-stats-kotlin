/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.marketplace.client.YearMonthDay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LatestSubscriptionTest {
    private fun range(start: String, end: String) = YearMonthDay.parse(start).rangeTo(YearMonthDay.parse(end))

    @Test
    fun consecutivePurchasesAreOneSubscription() {
        // e.g. a sale with a purchase until a date and a purchase, which starts on the next day
        val subscription = latestSubscription(listOf(range("2027-04-06", "2027-10-23"), range("2026-10-24", "2027-04-05")))
        assertEquals(range("2026-10-24", "2027-10-23"), subscription)
    }

    @Test
    fun aDayWithoutLicenseStartsANewSubscription() {
        val subscription = latestSubscription(listOf(range("2024-01-01", "2024-12-31"), range("2025-01-02", "2025-12-31")))
        assertEquals(range("2025-01-02", "2025-12-31"), subscription)
    }

    @Test
    fun overlappingPurchasesAreOneSubscription() {
        val subscription = latestSubscription(listOf(range("2024-01-01", "2024-12-31"), range("2024-06-01", "2025-05-31")))
        assertEquals(range("2024-01-01", "2025-05-31"), subscription)
    }

    @Test
    fun noPurchases() {
        assertNull(latestSubscription(emptyList()))
    }
}
