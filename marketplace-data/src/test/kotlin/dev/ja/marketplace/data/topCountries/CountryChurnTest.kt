/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.topCountries

import dev.ja.SalesGenerator
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.CustomerInfo
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
import dev.ja.marketplace.data.PercentageValue
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CountryChurnTest {
    private val today = YearMonthDay(2026, 10, 24)

    private val germany = CustomerInfo(1, "Germany", CustomerType.Individual)
    private val france = CustomerInfo(2, "France", CustomerType.Individual)
    private val canada = CustomerInfo(3, "Canada", CustomerType.Organization)
    private val unitedStates = CustomerInfo(4, "United States", CustomerType.Organization)

    private fun annual(license: String, customer: CustomerInfo, start: YearMonthDay, type: PluginSaleItemType = PluginSaleItemType.New): PluginSale {
        val sale = SalesGenerator.createSale(
            type = LicensePeriod.Annual,
            validity = start.rangeTo(start.add(1, 0, -1)),
            customer = customer,
            amount = FastMoney.of(100, MarketplaceCurrencies.USD),
            saleType = type,
        )
        val item = sale.lineItems.single() as SubscriptionPluginSaleItem
        return sale.copy(lineItems = listOf(item.copy(licenseIds = listOf(license))))
    }

    private fun churn(vararg sales: PluginSale): CountryChurn {
        return CountryChurn(LicenseInfo.createFrom(sales.toList()), today)
    }

    @Test
    fun churnOfTheLastTwelveMonths() {
        val churn = churn(
            // active a year ago and not renewed
            annual("de-1", germany, YearMonthDay(2025, 6, 1)),
            // active a year ago and renewed
            annual("de-2", germany, YearMonthDay(2025, 6, 1)),
            annual("de-2", germany, YearMonthDay(2026, 6, 1), PluginSaleItemType.Renew),
            // bought after the start of the 12 months
            annual("fr-1", france, YearMonthDay(2026, 1, 1)),
        )

        val germanyAnnual = churn.result("Germany", LicensePeriod.Annual)
        assertEquals(CountryChurn.Result(previouslyActive = 2, churned = 1), germanyAnnual)
        assertEquals(PercentageValue.of(1, 2), germanyAnnual.churnRate)

        assertNull(churn.result("France", LicensePeriod.Annual).churnRate)
        assertNull(churn.result("Germany", LicensePeriod.Monthly).churnRate)
    }

    @Test
    fun licenseRenewedInAnotherCountryIsNotChurned() {
        val churn = churn(
            annual("transferred", canada, YearMonthDay(2025, 6, 1)),
            annual("transferred", unitedStates, YearMonthDay(2026, 6, 1), PluginSaleItemType.Renew),
        )

        // the license belongs to the country of the customer, who held it a year ago
        assertEquals(CountryChurn.Result(previouslyActive = 1, churned = 0), churn.result("Canada", LicensePeriod.Annual))
        assertEquals(CountryChurn.Result(previouslyActive = 0, churned = 0), churn.result("United States", LicensePeriod.Annual))
    }
}
