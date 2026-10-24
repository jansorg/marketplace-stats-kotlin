/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.transfers

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.customer
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.licenses
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.sale
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LicenseTransfersTest {
    @Test
    fun transfersFromIndividualsToOrganizations() {
        val individual = customer(1)
        val organization = customer(2, CustomerType.Organization)
        val other = customer(3)
        val annual = LicensePeriod.Annual
        val licenses = licenses(
            sale(individual, "a", YearMonthDay(2024, 1, 1), 100, annual),
            sale(organization, "a", YearMonthDay(2025, 1, 1), 200, annual, PluginSaleItemType.Renew),
            sale(organization, "a", YearMonthDay(2026, 1, 1), 200, annual, PluginSaleItemType.Renew),
            // renewals by the same customer aren't transfers
            sale(other, "b", YearMonthDay(2025, 1, 1), 100, annual),
            sale(other, "b", YearMonthDay(2026, 1, 1), 100, annual, PluginSaleItemType.Renew),
        )

        val transfer = LicenseTransfers.find(licenses, today = YearMonthDay(2026, 6, 1)).single()
        assertEquals("a", transfer.licenseId)
        assertEquals(YearMonthDay(2025, 1, 1), transfer.date)
        assertEquals(CustomerType.Individual, transfer.from.type)
        assertEquals(CustomerType.Organization, transfer.to.type)
        assertEquals(YearMonthDay(2024, 12, 31), transfer.previousValidUntil)
        assertFalse(transfer.isFromCustomerActive)
    }
}
