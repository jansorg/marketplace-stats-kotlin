/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.customer
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.licenses
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.sale
import dev.ja.marketplace.exchangeRate.EcbExchangeRateData
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class AverageRevenuePerCustomerTest {
    @Test
    fun averageByCustomerType() {
        val organization = customer(1, CustomerType.Organization)
        val month = YearMonthDay(2025, 1, 1)
        val licenses = licenses(
            sale(organization, "a", month, 30),
            sale(organization, "b", month, 30),
            sale(customer(2), "c", month, 10),
            sale(customer(3), "d", month, 20),
        )
        val revenue = CustomerRecurringRevenue(licenses, ExchangeRates("USD", EcbExchangeRateData()), today = YearMonthDay(2025, 2, 15))

        val january = AverageRevenuePerCustomer.of(revenue, 0)
        assertEquals(3, january.customers)
        assertAmount(30, january.average!!)
        assertAmount(60, january.average(CustomerType.Organization)!!)
        assertAmount(15, january.average(CustomerType.Individual)!!)

        val february = AverageRevenuePerCustomer.of(revenue, 1)
        assertEquals(0, february.customers)
        assertNull(february.average)
    }

    private fun assertAmount(expected: Int, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected, got $actual")
    }
}
