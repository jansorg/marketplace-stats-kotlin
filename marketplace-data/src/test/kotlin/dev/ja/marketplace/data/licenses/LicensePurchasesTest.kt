/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.customer
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.licenses
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.sale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LicensePurchasesTest {
    @Test
    fun latestPurchaseOfAllCustomers() {
        // bought by customer 1, renewed by customer 2
        val purchases = LicensePurchases.of(
            licenses(
                sale(customer(1), "a", YearMonthDay(2024, 1, 1), 100, LicensePeriod.Annual),
                sale(customer(2), "a", YearMonthDay(2025, 1, 1), 100, LicensePeriod.Annual, PluginSaleItemType.Renew),
            )
        )
        assertEquals(2, purchases.latestPurchase("a")!!.sale.customer.code)
        assertEquals(YearMonthDay(2025, 12, 31), purchases.latestEnd("a"))
        assertEquals(YearMonthDay(2024, 1, 1), purchases.firstStart("a"))
        assertEquals(LicenseStatus.Active, purchases.status("a", YearMonthDay(2025, 12, 31)))
        assertEquals(LicenseStatus.Churned, purchases.status("a", YearMonthDay(2026, 1, 1)))
        assertEquals(setOf("a"), purchases.licenseIds)
    }
}
