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
import dev.ja.marketplace.data.chart.ChartMetric
import dev.ja.marketplace.data.chart.ChartWindow
import dev.ja.marketplace.data.chart.DailySeries
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
    private val dayValues = SubscriptionDailyValues.create(days, licenses, revenue, today, null)
    private val values = SubscriptionDailyValues.create(days, licenses, revenue, today, ChartWindow.Month) + dayValues
    private val yearValues = SubscriptionDailyValues.create(days, licenses, revenue, today, ChartWindow.Year) + dayValues

    @Test
    fun valuesAtTheEndOfEachMonthAreTheValuesOfTheTables() {
        // the current month is incomplete, its table values are the values at the end of the month
        for (monthIndex in 0 until revenue.currentMonthIndex) {
            val month = revenue.months[monthIndex]
            val index = (firstDay daysUntil YearMonthDay.lastOfMonth(month.year, month.month)).toInt()
            val label = revenue.monthLabel(monthIndex)

            val movements = MrrMovements.of(revenue, monthIndex)
            assertAmount(movements.new, ChartMetric.MrrNew, index, label)
            assertAmount(movements.expansion, ChartMetric.MrrExpansion, index, label)
            assertAmount(movements.contraction.negate(), ChartMetric.MrrContraction, index, label)
            assertAmount(movements.churned.negate(), ChartMetric.MrrChurned, index, label)
            assertAmount(movements.reactivation, ChartMetric.MrrReactivation, index, label)
            assertAmount(movements.netChange, ChartMetric.MrrNetChange, index, label)

            val average = AverageRevenuePerCustomer.of(revenue, monthIndex)
            assertEquals(BigDecimal.valueOf(average.customers.toLong()), value(ChartMetric.PayingCustomers, index), label)
            assertAmount(average.average, ChartMetric.AverageRevenue, index, label)
            assertAmount(average.average(CustomerType.Organization), ChartMetric.AverageRevenueOrganizations, index, label)
            assertAmount(average.average(CustomerType.Individual), ChartMetric.AverageRevenueIndividuals, index, label)

            val retention = RevenueRetention.of(revenue, monthIndex)
            if (retention == null) {
                assertNull(value(ChartMetric.PreviousCustomers, index, yearValues), label)
                assertNull(value(ChartMetric.NetRevenueRetention, index, yearValues), label)
            } else {
                assertEquals(BigDecimal.valueOf(retention.previousCustomers.toLong()), value(ChartMetric.PreviousCustomers, index, yearValues), label)
                assertPercentage(retention.customerRetention, ChartMetric.CustomerRetention, index, label, yearValues)
                assertPercentage(retention.grossRevenueRetention, ChartMetric.GrossRevenueRetention, index, label, yearValues)
                assertPercentage(retention.netRevenueRetention, ChartMetric.NetRevenueRetention, index, label, yearValues)
            }
        }
        assertTrue(revenue.currentMonthIndex > 24, "the test data must cover the retention of several months")
    }

    @Test
    fun movementsOfTheYearCompareWithTheMrrOfTheYearBefore() {
        // at the end of a year, the movements between the MRR at the end of the previous year and at the end of the year
        for (year in listOf(2024)) {
            val monthIndex = revenue.months.indexOf(YearMonthDay(year, 12, 1))
            val previousIndex = revenue.months.indexOf(YearMonthDay(year - 1, 12, 1))
            val movements = MrrMovements.of(revenue.customers(monthIndex), revenue.customers(previousIndex)) { customer ->
                (revenue.firstPaidMonthIndex(customer) ?: -1) > previousIndex
            }
            val index = (firstDay daysUntil YearMonthDay(year, 12, 31)).toInt()
            assertAmount(movements.new, ChartMetric.MrrNew, index, "$year", yearValues)
            assertAmount(movements.expansion, ChartMetric.MrrExpansion, index, "$year", yearValues)
            assertAmount(movements.contraction.negate(), ChartMetric.MrrContraction, index, "$year", yearValues)
            assertAmount(movements.churned.negate(), ChartMetric.MrrChurned, index, "$year", yearValues)
            assertAmount(movements.reactivation, ChartMetric.MrrReactivation, index, "$year", yearValues)
            assertAmount(movements.netChange, ChartMetric.MrrNetChange, index, "$year", yearValues)
            assertTrue(movements.new.signum() != 0 || movements.churned.signum() != 0, "the test data must have movements")
        }
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
        val values = SubscriptionDailyValues.create(days, licenses, revenue, YearMonthDay(2025, 4, 30), ChartWindow.Month) +
                SubscriptionDailyValues.create(days, licenses, revenue, YearMonthDay(2025, 4, 30), null)
        fun valueAt(metric: ChartMetric, date: YearMonthDay) =
            values.getValue(metric).values[(YearMonthDay(2025, 1, 1) daysUntil date).toInt()]

        assertEquals(BigDecimal("10.00"), valueAt(ChartMetric.MrrNew, YearMonthDay(2025, 1, 10)))
        // a month after the first sale, the customer isn't new anymore
        assertEquals(BigDecimal("0.00"), valueAt(ChartMetric.MrrNew, YearMonthDay(2025, 2, 10)))
        assertEquals(BigDecimal("5.00"), valueAt(ChartMetric.MrrExpansion, YearMonthDay(2025, 2, 10)))
        // the renewal ended at 2025-03-09
        assertEquals(BigDecimal("-15.00"), valueAt(ChartMetric.MrrChurned, YearMonthDay(2025, 3, 10)))
        assertNull(valueAt(ChartMetric.AverageRevenue, YearMonthDay(2025, 3, 10)))
    }

    @Test
    fun incompleteValues() {
        val incompleteFrom = (firstDay daysUntil YearMonthDay(2025, 9, 1)).toInt()
        assertTrue(values.values.all { it.incompleteFrom == incompleteFrom })
        assertTrue(values.values.all { it.values.size == days.size })
    }

    @Test
    fun noDays() {
        assertTrue(SubscriptionDailyValues.create(emptyList(), licenses, revenue, today, ChartWindow.Month).isEmpty())
    }

    private fun value(metric: ChartMetric, index: Int, values: Map<ChartMetric, DailySeries> = this.values): BigDecimal? {
        return values.getValue(metric).values[index]
    }

    private fun assertAmount(expected: BigDecimal?, metric: ChartMetric, index: Int, message: String, values: Map<ChartMetric, DailySeries> = this.values) {
        assertEquals(expected?.setScale(2, RoundingMode.HALF_UP), value(metric, index, values), "$metric $message")
    }

    private fun assertPercentage(expected: PercentageValue?, metric: ChartMetric, index: Int, message: String, values: Map<ChartMetric, DailySeries> = this.values) {
        assertEquals(expected?.value?.setScale(2, RoundingMode.HALF_UP), value(metric, index, values), "$metric $message")
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
        val values = SubscriptionDailyValues.create(days, licenses, revenue, today, ChartWindow.Month)

        val mayIndex = revenue.months.indexOfFirst { it == YearMonthDay(2024, 5, 1) }
        val movements = MrrMovements.of(revenue, mayIndex)
        val dayIndex = days.indexOf(YearMonthDay(2024, 5, 31))
        assertEquals(movements.new.setScale(2), values.getValue(ChartMetric.MrrNew).values[dayIndex])
        assertEquals(movements.reactivation.setScale(2), values.getValue(ChartMetric.MrrReactivation).values[dayIndex])
    }
}
