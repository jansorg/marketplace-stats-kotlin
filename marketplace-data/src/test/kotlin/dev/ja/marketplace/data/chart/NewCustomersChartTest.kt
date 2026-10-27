/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.chart

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.customer
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.sale
import dev.ja.marketplace.data.transfers.LicenseTransfers
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class NewCustomersChartTest {
    private val exchangeRates = ExchangeRates("USD")
    private val today = YearMonthDay(2025, 9, 17)

    private val sales: List<PluginSale> = listOf(
        // a first purchase, a renewal and a second license
        sale(customer(1), "a", YearMonthDay(2025, 1, 10), 10),
        sale(customer(1), "a", YearMonthDay(2025, 2, 10), 10, type = PluginSaleItemType.Renew),
        sale(customer(1), "b", YearMonthDay(2025, 3, 5), 10),
        // two licenses at the same day
        sale(customer(2), "c", YearMonthDay(2025, 1, 20), 10),
        sale(customer(2), "d", YearMonthDay(2025, 1, 20), 10),
        // a free license before the first paid license
        sale(customer(3), "e", YearMonthDay(2025, 1, 5), 0),
        sale(customer(3), "f", YearMonthDay(2025, 4, 2), 10),
        // a customer, who returns after the license ended
        sale(customer(4), "g", YearMonthDay(2024, 6, 1), 10),
        sale(customer(4), "h", YearMonthDay(2025, 5, 1), 10),
        // a license transferred from customer 6 to customer 5, who later buys an own license
        sale(customer(6), "t", YearMonthDay(2025, 2, 1), 10),
        sale(customer(5), "t", YearMonthDay(2025, 3, 1), 10, type = PluginSaleItemType.Renew),
        sale(customer(5), "u", YearMonthDay(2025, 6, 1), 10),
        // only a free license
        sale(customer(7), "v", YearMonthDay(2025, 2, 15), 0),
    ).sortedBy { it.date }

    private val licenses = LicenseInfo.createFrom(sales)

    private val trials = listOf(
        PluginTrial("trial-1", YearMonthDay(2025, 1, 3), customer(10)),
        PluginTrial("trial-2", YearMonthDay(2025, 2, 3), customer(10)),
        PluginTrial("trial-3", YearMonthDay(2025, 1, 3), customer(11)),
    )

    private val values = ChartMetricValues.create(sales, licenses, trials, emptyList(), exchangeRates, today)

    @Test
    fun newCustomersOfTheWindows() {
        // customers 1 and 2, the free license of customer 3 doesn't count
        assertEquals(2, value(ChartMetric.NewCustomers, YearMonthDay(2025, 1, 31), ChartWindow.CalendarMonth))
        assertEquals(1, value(ChartMetric.NewCustomers, YearMonthDay(2025, 1, 20), ChartWindow.Day))
        // the rolling month from January 6 contains customers 1, 2 and 6, the calendar month only customer 6
        assertEquals(3, value(ChartMetric.NewCustomers, YearMonthDay(2025, 2, 5), ChartWindow.Month))
        assertEquals(1, value(ChartMetric.NewCustomers, YearMonthDay(2025, 2, 5), ChartWindow.CalendarMonth))
        // the transfer to customer 5 and the renewal and second license of customer 1 don't count
        assertEquals(0, value(ChartMetric.NewCustomers, YearMonthDay(2025, 3, 31), ChartWindow.CalendarMonth))
        // the first paid license of customer 3
        assertEquals(1, value(ChartMetric.NewCustomers, YearMonthDay(2025, 4, 30), ChartWindow.CalendarMonth))
        // the returning customer 4 and the own license of customer 5 don't count
        assertEquals(0, value(ChartMetric.NewCustomers, YearMonthDay(2025, 5, 31), ChartWindow.CalendarMonth))
        assertEquals(0, value(ChartMetric.NewCustomers, YearMonthDay(2025, 6, 30), ChartWindow.CalendarMonth))
        assertEquals(1, value(ChartMetric.NewCustomers, YearMonthDay(2024, 12, 31), ChartWindow.CalendarYear))
        assertEquals(4, value(ChartMetric.NewCustomers, today, ChartWindow.CalendarYear))
    }

    @Test
    fun transferredLicensesAreTransfersOfTheTable() {
        val transfers = LicenseTransfers.find(licenses, today)
        assertEquals(listOf(5), transfers.map { it.to.code })
        val newCustomers = ChartMetricValues.newCustomerDays(licenses)
        assertEquals(5, newCustomers.size)
    }

    @Test
    fun averageOfNewCustomers() {
        // January, February and March: (2 + 1 + 0) / 3
        val average = values.average(ChartMetric.NewCustomers, ChartWindow.Month, 3)!!
        val index = values.days.indexOf(YearMonthDay(2025, 3, 31))
        assertEquals(0, BigDecimal.ONE.compareTo(average.values[index]))
    }

    @Test
    fun newTrialUsersCountTheFirstTrialOfACustomer() {
        assertEquals(2, value(ChartMetric.NewTrialUsers, YearMonthDay(2025, 1, 31), ChartWindow.CalendarMonth))
        assertEquals(0, value(ChartMetric.NewTrialUsers, YearMonthDay(2025, 2, 28), ChartWindow.CalendarMonth))
        assertEquals(1, value(ChartMetric.Trials, YearMonthDay(2025, 2, 28), ChartWindow.CalendarMonth))
    }

    @Test
    fun decimalsAndUnit() {
        assertEquals(1, ChartMetric.NewCustomers.fractionDigits)
        assertEquals(1, ChartMetric.NewTrialUsers.fractionDigits)
        assertEquals("count", values.series(ChartMetric.NewCustomers, ChartWindow.Month)!!.unit)
        assertEquals(values.days.lastIndex, values.series(ChartMetric.NewCustomers, ChartWindow.Month)!!.incompleteFrom)
    }

    @Test
    fun allPluginsCountTheCustomersOfEachPlugin() {
        // customer 1 is a new customer of both plugins
        val otherSales = listOf(
            sale(customer(1), "x", YearMonthDay(2025, 1, 15), 20, LicensePeriod.Annual),
            sale(customer(8), "y", YearMonthDay(2025, 1, 25), 20, LicensePeriod.Annual),
        )
        val otherLicenses = LicenseInfo.createFrom(otherSales)
        val all = ChartMetricValues.allPlugins(sales + otherSales, emptyList(), listOf(licenses, otherLicenses), exchangeRates, today)
        val index = all.days.indexOf(YearMonthDay(2025, 1, 31))
        assertEquals(0, BigDecimal(4).compareTo(all.series(ChartMetric.NewCustomers, ChartWindow.CalendarMonth)!!.values[index]))
        assertEquals(null, all.series(ChartMetric.NewTrialUsers, ChartWindow.CalendarMonth))
    }

    private fun value(metric: ChartMetric, day: YearMonthDay, window: ChartWindow): Int {
        return values.series(metric, window)!!.values[values.days.indexOf(day)]!!.toInt()
    }
}
