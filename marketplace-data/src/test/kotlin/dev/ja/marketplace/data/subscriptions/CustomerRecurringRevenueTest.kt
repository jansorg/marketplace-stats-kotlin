/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.customer
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.inCurrency
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.licenses
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.sale
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CustomerRecurringRevenueTest {
    private val today = YearMonthDay(2025, 9, 17)

    @Test
    fun overlappingSalesOfALicenseCountOnce() {
        val licenses = licenses(
            sale(customer(1), "a", YearMonthDay(2025, 1, 1), 120, LicensePeriod.Annual),
            // a monthly sale of the same license within the annual subscription, the annual sale ends later
            sale(customer(1), "a", YearMonthDay(2025, 6, 15), 20, LicensePeriod.Monthly, PluginSaleItemType.Renew),
        )
        val revenue = CustomerRecurringRevenue(licenses, ExchangeRates("USD"), today)

        val june = revenue.months.indexOf(YearMonthDay(2025, 6, 1))
        assertEquals(0, BigDecimal.TEN.compareTo(revenue.mrr(june, 1)))
        assertEquals(0, BigDecimal.TEN.compareTo(revenue.totalMrr(june)))
    }

    @Test
    fun transferredLicenseCountsForTheNewCustomer() {
        val licenses = licenses(
            sale(customer(1), "a", YearMonthDay(2025, 1, 1), 10),
            // renewed by an organization after a transfer
            sale(customer(2), "a", YearMonthDay(2025, 2, 1), 10, type = PluginSaleItemType.Renew),
        )
        val revenue = CustomerRecurringRevenue(licenses, ExchangeRates("USD"), today)

        assertEquals(0, BigDecimal.TEN.compareTo(revenue.mrr(0, 1)))
        assertEquals(0, BigDecimal.ZERO.compareTo(revenue.mrr(1, 1)))
        assertEquals(0, BigDecimal.TEN.compareTo(revenue.mrr(1, 2)))
        assertEquals(0, revenue.firstPaidMonthIndex(1))
        assertEquals(1, revenue.firstPaidMonthIndex(2))
        assertEquals(mapOf(2025 to listOf(1 to 0, 2 to 1)), revenue.cohorts())
    }

    @Test
    fun amountsInTheDisplayedCurrency() {
        // paid in EUR, the displayed currency is EUR, the amount isn't converted from USD
        val licenses = licenses(
            inCurrency(sale(customer(1), "a", YearMonthDay(2025, 1, 1), 120, LicensePeriod.Annual), 100, "EUR"),
            inCurrency(sale(customer(2), "b", YearMonthDay(2025, 1, 1), 12), 9, "EUR"),
        )
        val revenue = CustomerRecurringRevenue(licenses, ExchangeRates("EUR"), today)

        assertEquals("EUR", revenue.currency.currencyCode)
        assertEquals(0, BigDecimal("8.33").compareTo(revenue.mrr(0, 1).setScale(2, java.math.RoundingMode.HALF_UP)))
        assertEquals(0, BigDecimal(9).compareTo(revenue.mrr(0, 2)))
    }
}
