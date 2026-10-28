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
import dev.ja.marketplace.exchangeRate.EcbExchangeRateData
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MrrMovementsTest {
    private val exchangeRates = ExchangeRates("USD", EcbExchangeRateData())
    private val customer = customer(1)

    @Test
    fun movementsOfASingleCustomer() {
        val licenses = licenses(
            sale(customer, "a", YearMonthDay(2025, 1, 1), 10),
            // renewal and an additional license
            sale(customer, "a", YearMonthDay(2025, 2, 1), 10, type = PluginSaleItemType.Renew),
            sale(customer, "b", YearMonthDay(2025, 2, 1), 10),
            // only one license renewed, with a higher discount
            sale(customer, "a", YearMonthDay(2025, 3, 1), 8, type = PluginSaleItemType.Renew),
            // no license in April, returning in May
            sale(customer, "c", YearMonthDay(2025, 5, 1), 10),
        )
        val revenue = CustomerRecurringRevenue(licenses, exchangeRates, today = YearMonthDay(2025, 6, 15))
        assertEquals(6, revenue.months.size)
        assertEquals("2025-01", revenue.monthLabel(0))

        val january = MrrMovements.of(revenue, 0)
        assertAmount(10, january.mrr)
        assertAmount(10, january.new)

        val february = MrrMovements.of(revenue, 1)
        assertAmount(20, february.mrr)
        assertAmount(0, february.new)
        assertAmount(10, february.expansion)

        val march = MrrMovements.of(revenue, 2)
        assertAmount(8, march.mrr)
        assertAmount(12, march.contraction)
        assertAmount(-12, march.netChange)
        assertEquals(BigDecimal("0.00"), march.quickRatio)

        val april = MrrMovements.of(revenue, 3)
        assertAmount(0, april.mrr)
        assertAmount(8, april.churned)

        val may = MrrMovements.of(revenue, 4)
        assertAmount(10, may.reactivation)
        assertAmount(0, may.new)
        assertNull(may.quickRatio)
    }

    @Test
    fun annualLicensesContributeAMonthlyShare() {
        val licenses = licenses(
            sale(customer, "a", YearMonthDay(2025, 1, 1), 120, period = LicensePeriod.Annual),
            sale(customer(2), "b", YearMonthDay(2025, 2, 1), 5),
        )
        val revenue = CustomerRecurringRevenue(licenses, exchangeRates, today = YearMonthDay(2025, 3, 15))

        assertAmount(10, revenue.totalMrr(0))
        assertAmount(15, revenue.totalMrr(1))
        assertAmount(10, revenue.totalMrr(2))

        val february = MrrMovements.of(revenue, 1)
        assertAmount(5, february.new)
        assertAmount(0, february.expansion)
    }

    @Test
    fun noLicenses() {
        val revenue = CustomerRecurringRevenue(emptyList(), exchangeRates)
        assertEquals(0, revenue.months.size)
        assertEquals(-1, revenue.currentMonthIndex)
    }

    private fun assertAmount(expected: Int, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected, got $actual")
    }
}
