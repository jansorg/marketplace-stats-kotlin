/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.data.PercentageValue
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.customer
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.licenses
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.sale
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

class RevenueRetentionTest {
    private val exchangeRates = ExchangeRates("USD")

    @Test
    fun retentionComparedToAYearAgo() {
        val lastYear = YearMonthDay(2025, 1, 1)
        val thisYear = YearMonthDay(2026, 1, 1)
        val licenses = licenses(
            // same MRR
            sale(customer(1), "a", lastYear, 10),
            sale(customer(1), "a", thisYear, 10, type = PluginSaleItemType.Renew),
            // expansion
            sale(customer(2), "b", lastYear, 10),
            sale(customer(2), "b", thisYear, 20, type = PluginSaleItemType.Renew),
            // churned
            sale(customer(3), "c", lastYear, 10),
            // new customers don't count
            sale(customer(4), "d", thisYear, 10),
        )
        val revenue = CustomerRecurringRevenue(licenses, exchangeRates, today = YearMonthDay(2026, 2, 10))

        assertNull(RevenueRetention.of(revenue, 11))

        val retention = RevenueRetention.of(revenue, 12)!!
        assertEquals(3, retention.previousCustomers)
        assertEquals(2, retention.retainedCustomers)
        assertPercentage("66.67", retention.customerRetention)
        assertPercentage("100.00", retention.netRevenueRetention)
        assertPercentage("66.67", retention.grossRevenueRetention)
    }

    private fun assertPercentage(expected: String, actual: PercentageValue?) {
        assertEquals(BigDecimal(expected), actual!!.value.setScale(2, RoundingMode.HALF_UP))
    }
}
