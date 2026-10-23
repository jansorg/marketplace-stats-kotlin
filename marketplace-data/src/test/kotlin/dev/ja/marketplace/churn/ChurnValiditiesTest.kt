/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.SalesGenerator
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChurnValiditiesTest {
    private val firstValidity = YearMonthDay(2024, 1, 1).rangeTo(YearMonthDay(2024, 12, 31))

    private fun licenses(renewalStart: YearMonthDay, renewalType: PluginSaleItemType = PluginSaleItemType.Renew): List<LicenseInfo> {
        val first = SalesGenerator.createSale(type = LicensePeriod.Annual, validity = firstValidity)
        val licenseId = first.lineItems.single().licenseIds.single()
        val renewal = SalesGenerator.createSale(
            type = LicensePeriod.Annual,
            validity = renewalStart.rangeTo(renewalStart.add(1, 0, -1)),
            saleType = renewalType,
        ).let { sale ->
            val item = sale.lineItems.single() as SubscriptionPluginSaleItem
            sale.copy(lineItems = listOf(item.copy(licenseIds = listOf(licenseId))))
        }
        return LicenseInfo.createFrom(listOf(first, renewal))
    }

    /**
     * @return The number of licenses churned in January 2025.
     */
    private fun churnedInJanuary(licenses: List<LicenseInfo>): Int {
        val validities = ChurnValidities(licenses)
        val processor = LicenseChurnProcessor(YearMonthDay(2024, 12, 31), YearMonthDay(2025, 1, 31))
        processor.init()
        for (license in licenses) {
            processor.processValue(license, validities.validity(license)!!, license.isPaidLicense)
        }
        return processor.getResult(LicensePeriod.Annual).churnedItemCount
    }

    @Test
    fun renewalWithinGracePeriod() {
        // 59 days without a license
        val licenses = licenses(YearMonthDay(2025, 3, 1))
        assertEquals(YearMonthDay(2025, 2, 28), ChurnValidities(licenses).validity(licenses.first())!!.end)
        assertEquals(0, churnedInJanuary(licenses))
    }

    @Test
    fun renewalAfterGracePeriod() {
        val renewalStart = YearMonthDay(2024, 12, 31).add(0, 0, Marketplace.LICENSE_RENEWAL_GRACE_DAYS + 2)
        val licenses = licenses(renewalStart)
        assertEquals(firstValidity, ChurnValidities(licenses).validity(licenses.first()))
        assertEquals(1, churnedInJanuary(licenses))
    }

    @Test
    fun newLicenseIsNotARenewal() {
        val licenses = licenses(YearMonthDay(2025, 2, 1), PluginSaleItemType.New)
        assertEquals(firstValidity, ChurnValidities(licenses).validity(licenses.first()))
        assertEquals(1, churnedInJanuary(licenses))
    }

    @Test
    fun contiguousRenewal() {
        val licenses = licenses(YearMonthDay(2025, 1, 1))
        assertEquals(firstValidity, ChurnValidities(licenses).validity(licenses.first()))
        assertEquals(0, churnedInJanuary(licenses))
    }
}
