/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import org.javamoney.moneta.FastMoney

import dev.ja.marketplace.client.currency.MarketplaceCurrencies

import dev.ja.marketplace.client.LicenseInfo

import dev.ja.SalesGenerator

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.data.PercentageValue
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.customer
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.licenses
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.sale
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

class SubscriptionDailyValuesTest {
    private val exchangeRates = ExchangeRates("USD")
    private val today = YearMonthDay(2025, 9, 17)
    private val firstDay = YearMonthDay(2023, 1, 1)
    private val days = List((firstDay daysUntil today).toInt() + 1) { firstDay.add(0, 0, it) }

    /**
     * Monthly and annual licenses of individuals and organizations, with renewals, gaps and additional licenses.
     */
    private val sales: List<PluginSale> = run {
        val random = Random(7)
        (1..60).flatMap { customerId ->
            val customer = customer(customerId, if (customerId % 2 == 0) CustomerType.Organization else CustomerType.Individual)
            val period = if (customerId % 3 == 0) LicensePeriod.Annual else LicensePeriod.Monthly
            var start = YearMonthDay(2023, 1 + random.nextInt(12), 1 + random.nextInt(28))
            val result = mutableListOf<PluginSale>()
            var type = PluginSaleItemType.New
            while (start <= today && random.nextInt(30) != 0) {
                result += sale(customer, "license-$customerId", start, 5 + random.nextInt(50), period, type)
                // renewed directly or after a gap
                start = when (period) {
                    LicensePeriod.Annual -> start.add(1, 0, 0)
                    else -> start.add(0, 1, 0)
                }.add(0, 0, if (random.nextInt(5) == 0) random.nextInt(60) else 0)
                type = PluginSaleItemType.Renew
            }
            // an additional license of some customers
            if (customerId % 4 == 0) {
                result += sale(customer, "extra-$customerId", YearMonthDay(2024, 3, customerId % 28 + 1), 20, period)
            }
            result
        }.sortedBy { it.date }
    }

    private val licenses = licenses(*sales.toTypedArray())
    private val revenue = CustomerRecurringRevenue(licenses, exchangeRates, today)
    private val values = SubscriptionDailyValues.create(days, licenses, revenue, today)

    @Test
    fun valuesAtTheEndOfEachMonthAreTheValuesOfTheTables() {
        // the current month is incomplete, its table values are the values at the end of the month
        for (monthIndex in 0 until revenue.currentMonthIndex) {
            val month = revenue.months[monthIndex]
            val index = (firstDay daysUntil YearMonthDay.lastOfMonth(month.year, month.month)).toInt()
            val label = revenue.monthLabel(monthIndex)

            val movements = MrrMovements.of(revenue, monthIndex)
            assertAmount(movements.mrr, SubscriptionDailyColumn.Mrr, index, label)
            assertAmount(movements.new, SubscriptionDailyColumn.New, index, label)
            assertAmount(movements.expansion, SubscriptionDailyColumn.Expansion, index, label)
            assertAmount(movements.contraction.negate(), SubscriptionDailyColumn.Contraction, index, label)
            assertAmount(movements.churned.negate(), SubscriptionDailyColumn.Churned, index, label)
            assertAmount(movements.reactivation, SubscriptionDailyColumn.Reactivation, index, label)
            assertAmount(movements.netChange, SubscriptionDailyColumn.NetChange, index, label)

            val average = AverageRevenuePerCustomer.of(revenue, monthIndex)
            assertEquals(BigDecimal.valueOf(average.customers.toLong()), value(SubscriptionDailyColumn.PayingCustomers, index), label)
            assertAmount(average.average, SubscriptionDailyColumn.AverageRevenue, index, label)
            assertAmount(average.average(CustomerType.Organization), SubscriptionDailyColumn.AverageRevenueOrganizations, index, label)
            assertAmount(average.average(CustomerType.Individual), SubscriptionDailyColumn.AverageRevenueIndividuals, index, label)

            val retention = RevenueRetention.of(revenue, monthIndex)
            if (retention == null) {
                assertNull(value(SubscriptionDailyColumn.CustomersYearAgo, index), label)
                assertNull(value(SubscriptionDailyColumn.NetRevenueRetention, index), label)
            } else {
                assertEquals(BigDecimal.valueOf(retention.previousCustomers.toLong()), value(SubscriptionDailyColumn.CustomersYearAgo, index), label)
                assertPercentage(retention.customerRetention, SubscriptionDailyColumn.CustomerRetention, index, label)
                assertPercentage(retention.grossRevenueRetention, SubscriptionDailyColumn.GrossRevenueRetention, index, label)
                assertPercentage(retention.netRevenueRetention, SubscriptionDailyColumn.NetRevenueRetention, index, label)
            }
        }
        assertTrue(revenue.currentMonthIndex > 24, "the test data must cover the retention of several months")
    }

    @Test
    fun movementsWithinAMonthCompareWithTheSameDayOfThePreviousMonth() {
        val customer = customer(1)
        val licenses = licenses(
            sale(customer, "a", YearMonthDay(2025, 1, 10), 10),
            sale(customer, "a", YearMonthDay(2025, 2, 10), 15, type = PluginSaleItemType.Renew),
        )
        val revenue = CustomerRecurringRevenue(licenses, exchangeRates, YearMonthDay(2025, 4, 30))
        val days = List(120) { YearMonthDay(2025, 1, 1).add(0, 0, it) }
        val values = SubscriptionDailyValues.create(days, licenses, revenue, YearMonthDay(2025, 4, 30))
        fun valueAt(column: SubscriptionDailyColumn, date: YearMonthDay) =
            values.getValue(column).values[(YearMonthDay(2025, 1, 1) daysUntil date).toInt()]

        assertEquals(BigDecimal("10.00"), valueAt(SubscriptionDailyColumn.New, YearMonthDay(2025, 1, 10)))
        // a month after the first sale, the customer isn't new anymore
        assertEquals(BigDecimal("0.00"), valueAt(SubscriptionDailyColumn.New, YearMonthDay(2025, 2, 10)))
        assertEquals(BigDecimal("5.00"), valueAt(SubscriptionDailyColumn.Expansion, YearMonthDay(2025, 2, 10)))
        // the renewal ended at 2025-03-09
        assertEquals(BigDecimal("0.00"), valueAt(SubscriptionDailyColumn.Mrr, YearMonthDay(2025, 3, 10)))
        assertEquals(BigDecimal("-15.00"), valueAt(SubscriptionDailyColumn.Churned, YearMonthDay(2025, 3, 10)))
        assertNull(valueAt(SubscriptionDailyColumn.AverageRevenue, YearMonthDay(2025, 3, 10)))
    }

    @Test
    fun incompleteValues() {
        val incompleteFrom = (firstDay daysUntil YearMonthDay(2025, 9, 1)).toInt()
        assertTrue(values.values.all { it.incompleteFrom == incompleteFrom })
        assertTrue(values.values.all { it.values.size == days.size })
    }

    @Test
    fun noDays() {
        assertTrue(SubscriptionDailyValues.create(emptyList(), licenses, revenue, today).isEmpty())
    }

    private fun value(column: SubscriptionDailyColumn, index: Int): BigDecimal? {
        return values.getValue(column).values[index]
    }

    private fun assertAmount(expected: BigDecimal?, column: SubscriptionDailyColumn, index: Int, message: String) {
        assertEquals(expected?.setScale(2, RoundingMode.HALF_UP), value(column, index), "$column $message")
    }

    private fun assertPercentage(expected: PercentageValue?, column: SubscriptionDailyColumn, index: Int, message: String) {
        assertEquals(expected?.value?.setScale(2, RoundingMode.HALF_UP), value(column, index), "$column $message")
    }

    @Test
    fun customerWithAFirstLicenseWithoutAnEndOfAMonthIsNewInTheFirstPaidMonth() {
        // the first license covers no end of a month, the table counts the customer as new in May
        val customer = customer(1)
        val sales = listOf(
            SalesGenerator.createSale(
                type = LicensePeriod.Monthly, saleDate = YearMonthDay(2024, 3, 5),
                validity = YearMonthDay(2024, 3, 5).rangeTo(YearMonthDay(2024, 3, 20)), customer = customer,
                amount = FastMoney.of(10, MarketplaceCurrencies.USD),
            ),
            sale(customer, "license-2", YearMonthDay(2024, 5, 10), 10, LicensePeriod.Monthly),
        )
        val licenses = LicenseInfo.createFrom(sales)
        val today = YearMonthDay(2024, 7, 31)
        val revenue = CustomerRecurringRevenue(licenses, exchangeRates, today)
        val firstDay = YearMonthDay(2024, 3, 1)
        val days = List((firstDay daysUntil today).toInt() + 1) { firstDay.add(0, 0, it) }
        val values = SubscriptionDailyValues.create(days, licenses, revenue, today)

        val mayIndex = revenue.months.indexOfFirst { it == YearMonthDay(2024, 5, 1) }
        val movements = MrrMovements.of(revenue, mayIndex)
        val dayIndex = days.indexOf(YearMonthDay(2024, 5, 31))
        assertEquals(movements.new.setScale(2), values.getValue(SubscriptionDailyColumn.New).values[dayIndex])
        assertEquals(movements.reactivation.setScale(2), values.getValue(SubscriptionDailyColumn.Reactivation).values[dayIndex])
    }
}
