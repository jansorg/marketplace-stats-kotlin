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

class RealizedLifetimeValueTest {
    @Test
    fun averagePaymentsWithinTheFirstYears() {
        val sales = listOf(
            sale(customer(1), "a", YearMonthDay(2024, 3, 10), 100, LicensePeriod.Annual),
            sale(customer(1), "a", YearMonthDay(2025, 3, 10), 80, LicensePeriod.Annual, PluginSaleItemType.Renew),
            sale(customer(2), "b", YearMonthDay(2024, 9, 1), 60, LicensePeriod.Annual),
        )
        val today = YearMonthDay(2026, 1, 15)
        val revenue = CustomerRecurringRevenue(licenses(*sales.toTypedArray()), ExchangeRates("USD"), today)
        val payments = sales.map { CustomerPayment(it.customer.code, it.date, it.amountUSD.number.numberValue(BigDecimal::class.java)) }

        val cohorts = RealizedLifetimeValue.of(revenue, payments, today)
        assertEquals(1, cohorts.size)
        assertEquals(2024, cohorts[0].year)
        assertEquals(2, cohorts[0].customers)

        // both customers: until March 2025 and September 2025, the renewal of March 2025 is not included
        val firstYear = cohorts[0].years[0]
        assertEquals(2, firstYear.customers)
        assertEquals(0, BigDecimal(80).compareTo(firstYear.averageAmount))
        assertFalse(firstYear.isIncomplete)

        // only the 1st customer's second year ended in March 2026, which is in the future
        assertEquals(1, cohorts[0].years.size)
    }

    @Test
    fun incompleteIfThePeriodOfSomeCustomersDidNotEnd() {
        val sales = listOf(
            sale(customer(1), "a", YearMonthDay(2024, 1, 1), 100, LicensePeriod.Annual),
            sale(customer(2), "b", YearMonthDay(2024, 11, 1), 50, LicensePeriod.Annual),
        )
        val today = YearMonthDay(2025, 6, 1)
        val revenue = CustomerRecurringRevenue(licenses(*sales.toTypedArray()), ExchangeRates("USD"), today)
        val payments = sales.map { CustomerPayment(it.customer.code, it.date, it.amountUSD.number.numberValue(BigDecimal::class.java)) }

        val firstYear = RealizedLifetimeValue.of(revenue, payments, today).single().years.single()
        assertEquals(1, firstYear.customers)
        assertEquals(0, BigDecimal(100).compareTo(firstYear.averageAmount))
        assertTrue(firstYear.isIncomplete)
    }
}
