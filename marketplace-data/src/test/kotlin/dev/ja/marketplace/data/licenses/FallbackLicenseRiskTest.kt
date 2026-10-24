/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.SalesGenerator
import dev.ja.marketplace.TestCustomers
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.CustomerInfo
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginReleaseDate
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FallbackLicenseRiskTest {
    private val today = YearMonthDay(2026, 10, 1)
    private val exchangeRates = ExchangeRates("USD")
    private val releases = listOf(
        PluginReleaseDate("10", YearMonthDay(2024, 3, 1)),
        PluginReleaseDate("20", YearMonthDay(2025, 6, 1)),
        // a duplicate entry of another update of the same version
        PluginReleaseDate("20", YearMonthDay(2025, 6, 1)),
    )

    private fun sale(
        licenseId: String,
        validity: YearMonthDayRange,
        period: LicensePeriod = LicensePeriod.Annual,
        amount: Int = 100,
        customer: CustomerInfo = TestCustomers.PersonDummy,
    ): PluginSale {
        val sale = SalesGenerator.createSale(
            type = period,
            saleDate = validity.start,
            validity = validity,
            customer = customer,
            amount = FastMoney.of(amount, MarketplaceCurrencies.USD),
            saleType = PluginSaleItemType.Renew,
        )
        val item = sale.lineItems.single() as SubscriptionPluginSaleItem
        return sale.copy(lineItems = listOf(item.copy(licenseIds = listOf(licenseId))))
    }

    private fun annual(licenseId: String, start: YearMonthDay, amount: Int = 100, customer: CustomerInfo = TestCustomers.PersonDummy): PluginSale {
        return sale(licenseId, start.rangeTo(start.add(1, 0, -1)), amount = amount, customer = customer)
    }

    private fun monthly(licenseId: String, start: YearMonthDay, months: Int, amount: Int = 10): List<PluginSale> {
        return (0 until months).map { month ->
            val monthStart = start.add(0, month, 0)
            sale(licenseId, monthStart.rangeTo(monthStart.add(0, 1, -1)), LicensePeriod.Monthly, amount)
        }
    }

    private fun risk(sales: List<PluginSale>, releases: List<PluginReleaseDate> = this.releases): FallbackLicenseRisk {
        return FallbackLicenseRisk(LicenseInfo.createFrom(sales), releases, exchangeRates, today)
    }

    private fun usd(amount: Int) = FastMoney.of(amount, MarketplaceCurrencies.USD)

    @Test
    fun latestVersion() {
        val risk = risk(emptyList())
        assertEquals(YearMonthDay(2025, 6, 1), risk.latestRelease?.date)
        assertEquals(YearMonthDay(2026, 6, 1), risk.latestReleaseYearOldDate)
        assertTrue(risk.isLatestReleaseYearOld)

        val newRelease = risk(emptyList(), releases + PluginReleaseDate("30", YearMonthDay(2026, 3, 1)))
        assertEquals(YearMonthDay(2027, 3, 1), newRelease.latestReleaseYearOldDate)
        assertFalse(newRelease.isLatestReleaseYearOld)

        // the version is a year old on the day of its release a year later
        val yearOld = risk(emptyList(), listOf(PluginReleaseDate("30", YearMonthDay(2025, 10, 1))))
        assertTrue(yearOld.isLatestReleaseYearOld)
    }

    @Test
    fun licenseWithTheLatestVersionIsAffected() {
        // fallback date 2025-12-01, after the release of the latest version
        val risk = risk(listOf(annual("a", YearMonthDay(2024, 12, 1)), annual("a", YearMonthDay(2025, 12, 1), amount = 80)))
        val license = risk.affectedLicenses.single()
        assertEquals("a", license.licenseId)
        assertEquals("20", license.fallbackVersion.release?.version)
        assertEquals(YearMonthDay(2026, 11, 30), license.fallbackVersion.subscriptionEnd)
        // the latest purchase
        assertEquals(usd(80), license.annualRevenue)
        assertEquals(usd(80), risk.revenueAtRisk)
        assertEquals(1, risk.affectedCustomers.size)
        assertEquals(1, risk.activeLicenseCount)
        assertEquals(100.0, risk.revenueAtRiskShare.value.toDouble(), 0.001)
    }

    @Test
    fun licenseWithAnEarlierVersionIsNotAffected() {
        // fallback date 2025-12-01, before the release of the latest version
        val newRelease = releases + PluginReleaseDate("30", YearMonthDay(2026, 3, 1))
        val risk = risk(listOf(annual("a", YearMonthDay(2024, 12, 1)), annual("a", YearMonthDay(2025, 12, 1))), newRelease)
        assertTrue(risk.affectedLicenses.isEmpty())
        assertEquals(1, risk.activeLicenseCount)
        assertEquals(usd(100), risk.activeAnnualRevenue)
        assertEquals(usd(0), risk.revenueAtRisk)
    }

    @Test
    fun monthlyLicenseWithoutTwelveMonthsIsNotEligible() {
        val risk = risk(monthly("m", YearMonthDay(2026, 1, 1), 10))
        assertTrue(risk.affectedLicenses.isEmpty())
        assertEquals(1, risk.activeLicenseCount)
        // the monthly license counts 12 times
        assertEquals(usd(120), risk.activeAnnualRevenue)
    }

    @Test
    fun newAnnualLicenseIsAffected() {
        // the purchase of an annual subscription covers the 12 months before its end
        val risk = risk(listOf(annual("a", YearMonthDay(2026, 9, 1))))
        assertEquals("20", risk.affectedLicenses.single().fallbackVersion.release?.version)
    }

    @Test
    fun monthlyLicenseIsAnnualized() {
        val risk = risk(monthly("m", YearMonthDay(2025, 9, 1), 14))
        val license = risk.affectedLicenses.single()
        assertEquals(LicensePeriod.Monthly, license.period)
        assertEquals(usd(120), license.annualRevenue)
    }

    @Test
    fun churnedLicenseIsNotAffected() {
        // the fallback version is the latest version, but the subscription ended
        val risk = risk(listOf(annual("a", YearMonthDay(2025, 9, 1))))
        assertTrue(risk.affectedLicenses.isEmpty())
        assertEquals(0, risk.activeLicenseCount)
    }

    @Test
    fun transferredLicenseBelongsToTheLatestCustomer() {
        // renewed by an organization, the subscription is uninterrupted
        val risk = risk(
            listOf(
                annual("a", YearMonthDay(2024, 12, 1), amount = 100, customer = TestCustomers.PersonDummy),
                annual("a", YearMonthDay(2025, 12, 1), amount = 200, customer = TestCustomers.OrganizationDummy),
            )
        )
        val license = risk.affectedLicenses.single()
        assertEquals(TestCustomers.OrganizationDummy.code, license.customer.code)
        assertEquals(YearMonthDay(2024, 12, 1), license.fallbackVersion.subscriptionStart)
        assertEquals(usd(200), license.annualRevenue)

        val customer = risk.affectedCustomers.single()
        assertEquals(TestCustomers.OrganizationDummy.code, customer.customer.code)
        assertEquals(1, customer.licenses.size)
    }

    @Test
    fun customersAreOrderedByRevenueAtRisk() {
        val risk = risk(
            listOf(
                annual("a", YearMonthDay(2025, 11, 1), amount = 100, customer = TestCustomers.PersonDummy),
                annual("b", YearMonthDay(2025, 12, 1), amount = 150, customer = TestCustomers.OrganizationDummy),
                annual("c", YearMonthDay(2025, 10, 15), amount = 100, customer = TestCustomers.OrganizationDummy),
            )
        )
        assertEquals(listOf("c", "a", "b"), risk.affectedLicenses.map { it.licenseId })
        assertEquals(listOf(TestCustomers.OrganizationDummy.code, TestCustomers.PersonDummy.code), risk.affectedCustomers.map { it.customer.code })
        assertEquals(usd(250), risk.affectedCustomers.first().annualRevenue)
        assertEquals(YearMonthDay(2026, 10, 14), risk.affectedCustomers.first().firstSubscriptionEnd)
        assertEquals(usd(350), risk.revenueAtRisk)
    }

    @Test
    fun withoutReleasesNoLicenseIsAffected() {
        val risk = risk(listOf(annual("a", YearMonthDay(2025, 12, 1))), emptyList())
        assertNull(risk.latestRelease)
        assertNull(risk.latestReleaseYearOldDate)
        assertFalse(risk.isLatestReleaseYearOld)
        assertTrue(risk.affectedLicenses.isEmpty())
        assertEquals(1, risk.activeLicenseCount)
    }

    @Test
    fun freeLicensesAreIgnored() {
        val risk = risk(listOf(annual("a", YearMonthDay(2024, 12, 1), amount = 0), annual("a", YearMonthDay(2025, 12, 1), amount = 0)))
        assertTrue(risk.affectedLicenses.isEmpty())
        assertEquals(0, risk.activeLicenseCount)
    }
}
