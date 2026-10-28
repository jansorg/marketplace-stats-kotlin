/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.SalesGenerator
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSaleItemDiscount
import dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
import dev.ja.marketplace.exchangeRate.EcbExchangeRateData
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecurringRevenueTrackerTest {
    private val exchangeRates = ExchangeRates("USD", EcbExchangeRateData())
    private val month = YearMonthDayRange.ofMonth(2025, 9)

    @Test
    fun discountsAreNotAppliedTwice() {
        // annual license, list price 120 USD, billed 60 USD because of a 50% promotional discount
        val sale = SalesGenerator.createSale(
            type = LicensePeriod.Annual,
            saleDate = YearMonthDay(2025, 3, 1),
            amount = FastMoney.of(60, MarketplaceCurrencies.USD),
        ).let { sale ->
            val item = sale.lineItems.single() as SubscriptionPluginSaleItem
            sale.copy(lineItems = listOf(item.copy(discountDescriptions = listOf(PluginSaleItemDiscount("Promotion", 50.0)))))
        }
        val licenses = LicenseInfo.createFrom(listOf(sale))

        val mrr = MonthlyRecurringRevenueTracker(month, exchangeRates).also { t -> licenses.forEach(t::processLicenseSale) }
        assertEquals(FastMoney.of(5, MarketplaceCurrencies.USD), mrr.getResult().amounts.getTotalAmountUSD())

        val arr = AnnualRecurringRevenueTracker(month, exchangeRates).also { t -> licenses.forEach(t::processLicenseSale) }
        assertEquals(FastMoney.of(60, MarketplaceCurrencies.USD), arr.getResult().amounts.getTotalAmountUSD())
    }

    @Test
    fun onlyLicensesValidAtTheEndOfThePeriod() {
        val monthly = SalesGenerator.createSale(
            type = LicensePeriod.Monthly,
            saleDate = YearMonthDay(2025, 9, 10),
            amount = FastMoney.of(10, MarketplaceCurrencies.USD),
        )
        val expired = SalesGenerator.createSale(
            type = LicensePeriod.Monthly,
            validity = YearMonthDay(2025, 8, 20).rangeTo(YearMonthDay(2025, 9, 19)),
            amount = FastMoney.of(7, MarketplaceCurrencies.USD),
        )
        val licenses = LicenseInfo.createFrom(listOf(monthly, expired))

        val mrr = MonthlyRecurringRevenueTracker(month, exchangeRates).also { t -> licenses.forEach(t::processLicenseSale) }
        assertEquals(FastMoney.of(10, MarketplaceCurrencies.USD), mrr.getResult().amounts.getTotalAmountUSD())

        val arr = AnnualRecurringRevenueTracker(month, exchangeRates).also { t -> licenses.forEach(t::processLicenseSale) }
        assertEquals(FastMoney.of(120, MarketplaceCurrencies.USD), arr.getResult().amounts.getTotalAmountUSD())
    }
}
