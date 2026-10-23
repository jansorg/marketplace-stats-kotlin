/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.SalesGenerator
import dev.ja.marketplace.TestCustomers
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItemDiscount
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrialTrackerTest {
    private val trialDays = 30
    private val trialDate = YearMonthDay(2025, 3, 1)
    private val trial = PluginTrial("trial-1", trialDate, TestCustomers.PersonDummy)
    private val today = YearMonthDay(2026, 1, 1)

    private fun sale(daysAfterTrial: Int, saleType: PluginSaleItemType = PluginSaleItemType.New, free: Boolean = false): PluginSale {
        val sale = SalesGenerator.createSale(
            saleDate = trialDate.add(0, 0, daysAfterTrial),
            customer = TestCustomers.PersonDummy,
            amount = FastMoney.of(if (free) 0 else 100, MarketplaceCurrencies.USD),
            saleType = saleType,
        )
        if (!free) {
            return sale
        }
        val item = sale.lineItems.single() as SubscriptionPluginSaleItem
        return sale.copy(lineItems = listOf(item.copy(discountDescriptions = listOf(PluginSaleItemDiscount("Free license", 100.0)))))
    }

    private fun result(vararg sales: PluginSale): TrialTrackerInfo {
        val tracker = SimpleTrialTracker()
        tracker.init(listOf(trial))
        sales.forEach(tracker::processSale)
        return tracker.getResultWithinConversionPeriod(YearMonthDayRange.MAX, trialDays, today)
    }

    @Test
    fun paidNewLicenseConverts() {
        assertEquals(1, result(sale(10)).convertedTrialsCount)
    }

    @Test
    fun freeLicenseAndRenewalDoNotConvert() {
        assertEquals(0, result(sale(10, free = true)).convertedTrialsCount)
        assertEquals(0, result(sale(10, saleType = PluginSaleItemType.Renew)).convertedTrialsCount)
        // a paid new license after a free license converts
        assertEquals(1, result(sale(5, free = true), sale(20)).convertedTrialsCount)
    }

    @Test
    fun conversionPeriod() {
        val conversionDays = trialDays + Marketplace.TRIAL_CONVERSION_EXTRA_DAYS
        assertEquals(1, result(sale(conversionDays)).convertedTrialsCount)
        assertEquals(0, result(sale(conversionDays + 1)).convertedTrialsCount)
    }

    @Test
    fun pendingTrials() {
        val tracker = SimpleTrialTracker()
        val recentTrial = PluginTrial("trial-2", today.add(0, 0, -10), TestCustomers.OrganizationDummy)
        tracker.init(listOf(trial, recentTrial))

        val result = tracker.getResultWithinConversionPeriod(YearMonthDayRange.MAX, trialDays, today)
        assertEquals(2, result.totalTrials)
        assertEquals(1, result.pendingTrials)
        assertTrue(result.isIncomplete)

        val oldTrialsOnly = tracker.getResultWithinConversionPeriod(YearMonthDayRange.ofMonth(2025, 3), trialDays, today)
        assertEquals(0, oldTrialsOnly.pendingTrials)
        assertFalse(oldTrialsOnly.isIncomplete)
    }
}
