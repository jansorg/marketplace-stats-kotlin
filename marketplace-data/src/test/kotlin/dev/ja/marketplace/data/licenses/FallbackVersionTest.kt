/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.SalesGenerator
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginReleaseDate
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FallbackVersionTest {
    private val releases = listOf(
        PluginReleaseDate("1.0", YearMonthDay(2023, 6, 1)),
        PluginReleaseDate("2.0", YearMonthDay(2024, 3, 1)),
        PluginReleaseDate("3.0", YearMonthDay(2025, 6, 1)),
    )

    private val licenseId = "license-1"

    private fun sale(validity: YearMonthDayRange, period: LicensePeriod = LicensePeriod.Annual, amount: Int = 100): PluginSale {
        val sale = SalesGenerator.createSale(
            type = period,
            validity = validity,
            amount = FastMoney.of(amount, MarketplaceCurrencies.USD),
            saleType = PluginSaleItemType.Renew,
        )
        val item = sale.lineItems.single() as SubscriptionPluginSaleItem
        return sale.copy(lineItems = listOf(item.copy(licenseIds = listOf(licenseId))))
    }

    private fun annual(start: YearMonthDay, amount: Int = 100): PluginSale {
        return sale(start.rangeTo(start.add(1, 0, -1)), amount = amount)
    }

    private fun fallbackVersion(vararg sales: PluginSale): FallbackVersion? {
        return FallbackVersion.of(licenseId, LicenseInfo.createFrom(sales.toList()), releases)
    }

    @Test
    fun annualSubscription() {
        val version = fallbackVersion(annual(YearMonthDay(2024, 3, 2)))!!
        assertTrue(version.isEligible)
        assertEquals(YearMonthDay(2024, 3, 2), version.fallbackDate)
        assertEquals("2.0", version.release?.version)
        assertFalse(version.isLatestVersion)
    }

    @Test
    fun versionReleasedAtTheFallbackDateIsNotIncluded() {
        val version = fallbackVersion(annual(YearMonthDay(2024, 3, 1)))!!
        assertEquals(YearMonthDay(2024, 3, 1), version.fallbackDate)
        assertEquals("1.0", version.release?.version)
    }

    @Test
    fun renewalsMoveTheFallbackDate() {
        val version = fallbackVersion(
            annual(YearMonthDay(2024, 1, 1)),
            annual(YearMonthDay(2025, 1, 1)),
            annual(YearMonthDay(2026, 1, 1)),
        )!!
        assertEquals(YearMonthDay(2024, 1, 1), version.subscriptionStart)
        assertEquals(YearMonthDay(2026, 12, 31), version.subscriptionEnd)
        assertEquals(YearMonthDay(2026, 1, 1), version.fallbackDate)
        assertEquals("3.0", version.release?.version)
        assertTrue(version.isLatestVersion)
    }

    @Test
    fun monthlySubscriptionsNeedTwelveMonths() {
        val months = (0 until 11).map { month ->
            val start = YearMonthDay(2024, 1, 1).add(0, month, 0)
            sale(start.rangeTo(start.add(0, 1, -1)), LicensePeriod.Monthly)
        }
        val notEligible = fallbackVersion(*months.toTypedArray())!!
        assertFalse(notEligible.isEligible)
        assertNull(notEligible.release)

        val twelfth = sale(YearMonthDay(2024, 12, 1).rangeTo(YearMonthDay(2024, 12, 31)), LicensePeriod.Monthly)
        val eligible = fallbackVersion(*(months + twelfth).toTypedArray())!!
        assertTrue(eligible.isEligible)
        assertEquals(YearMonthDay(2024, 1, 1), eligible.fallbackDate)
        assertEquals("1.0", eligible.release?.version)
    }

    @Test
    fun interruptionStartsANewSubscription() {
        val version = fallbackVersion(
            annual(YearMonthDay(2024, 1, 1)),
            sale(YearMonthDay(2025, 1, 2).rangeTo(YearMonthDay(2025, 6, 30)), LicensePeriod.Monthly),
        )!!
        assertEquals(YearMonthDay(2025, 1, 2), version.subscriptionStart)
        assertFalse(version.isEligible)
    }

    @Test
    fun freeLicensesAreNotPaidSubscriptions() {
        assertNull(fallbackVersion(annual(YearMonthDay(2024, 1, 1), amount = 0)))
    }

    @Test
    fun noVersionBeforeTheFallbackDate() {
        val version = fallbackVersion(annual(YearMonthDay(2023, 1, 1)))!!
        assertTrue(version.isEligible)
        assertNull(version.release)
    }
}
