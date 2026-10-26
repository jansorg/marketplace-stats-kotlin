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
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CohortRetentionTest {
    private val exchangeRates = ExchangeRates("USD")

    @Test
    fun customersAreGroupedByTheirFirstYear() {
        val licenses = licenses(
            // renewed once, churned in the 2nd year
            sale(customer(1), "a", YearMonthDay(2024, 3, 1), 120, period = LicensePeriod.Annual),
            sale(customer(1), "a", YearMonthDay(2025, 3, 1), 240, period = LicensePeriod.Annual, type = PluginSaleItemType.Renew),
            // not renewed
            sale(customer(2), "b", YearMonthDay(2024, 6, 1), 120, period = LicensePeriod.Annual),
            // first anniversary in the future
            sale(customer(3), "c", YearMonthDay(2025, 9, 1), 120, period = LicensePeriod.Annual),
        )
        val revenue = CustomerRecurringRevenue(licenses, exchangeRates, today = YearMonthDay(2026, 4, 15))
        val cohorts = CohortRetention.of(revenue)
        assertEquals(listOf(2024, 2025), cohorts.map { it.year })

        val cohort2024 = cohorts[0]
        assertEquals(2, cohort2024.customers)
        assertEquals(0, BigDecimal(20).compareTo(cohort2024.initialMrr))
        assertEquals(2, cohort2024.anniversaries.size)

        val firstYear = cohort2024.anniversaries[0]
        assertEquals(2, firstYear.customers)
        assertEquals(1, firstYear.retainedCustomers)
        assertEquals(BigDecimal("100.00"), firstYear.revenueRetention!!.value.setScale(2))
        assertFalse(firstYear.isIncomplete)

        // only the anniversary of the 1st customer in March 2026 is not in the future
        val secondYear = cohort2024.anniversaries[1]
        assertEquals(1, secondYear.customers)
        assertEquals(0, secondYear.retainedCustomers)
        assertTrue(secondYear.isIncomplete)

        assertEquals(0, cohorts[1].anniversaries.size)
    }
}
