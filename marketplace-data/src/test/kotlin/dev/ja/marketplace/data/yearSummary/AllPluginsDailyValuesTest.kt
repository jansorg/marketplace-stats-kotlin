/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.customer
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.sale
import dev.ja.marketplace.data.trackers.AnnualRecurringRevenueTracker
import dev.ja.marketplace.data.trackers.MonthlyRecurringRevenueTracker
import dev.ja.marketplace.data.trackers.PaymentAmountTracker
import dev.ja.marketplace.exchangeRate.ExchangeRates
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import javax.money.MonetaryAmount

class AllPluginsDailyValuesTest {
    private val exchangeRates = ExchangeRates("USD")
    private val today = YearMonthDay(2025, 9, 17)

    // sales of two plugins
    private val sales: List<PluginSale> = (0 until 300).map { index ->
        sale(customer(index % 20), "license-$index", YearMonthDay(2023, 2, 3).add(0, 0, index * 3), 5 + index % 40)
    }
    private val downloads = (0..1000).map { DailyDownload(YearMonthDay(2023, 1, 1).add(0, 0, it), (it % 9).toLong()) }

    private val licenses = LicenseInfo.createFrom(sales)
    private val values = AllPluginsDailyValues.create(sales, downloads, listOf(licenses), exchangeRates, today)

    @Test
    fun valuesAtTheEndOfAYearAreTheSumsOfTheYear() = runBlocking {
        val index = (values.days.first() daysUntil YearMonthDay(2024, 12, 31)).toInt()
        val year = PaymentAmountTracker(YearMonthDayRange.ofYear(2024), exchangeRates)
        sales.forEach { year.add(it.date, it.amountUSD, it.amount) }

        assertEquals(amount(year.totalAmount), value(AllPluginsDailyValues.SALES, index))
        assertEquals(amount(year.feesAmount), value(AllPluginsDailyValues.FEES, index))
        assertEquals(amount(year.paidAmount), value(AllPluginsDailyValues.PAID, index))
        assertEquals(
            BigDecimal.valueOf(downloads.filter { it.day.year == 2024 }.sumOf { it.downloads }),
            value(AllPluginsDailyValues.DOWNLOADS, index),
        )

        // the recurring revenue at the end of the year, like the table
        val mrr = MonthlyRecurringRevenueTracker(YearMonthDayRange.ofYear(2024), exchangeRates)
        val arr = AnnualRecurringRevenueTracker(YearMonthDayRange.ofYear(2024), exchangeRates)
        licenses.forEach { mrr.processLicenseSale(it); arr.processLicenseSale(it) }
        assertEquals(amount(mrr.getResult().amounts.getTotalAmount()), value(AllPluginsDailyValues.MRR, index))
        assertEquals(amount(arr.getResult().amounts.getTotalAmount()), value(AllPluginsDailyValues.ARR, index))
    }

    @Test
    fun valuesWithinAYearAreTheSumsOfTheYearBeforeTheDay() {
        val day = YearMonthDay(2025, 3, 10)
        val index = (values.days.first() daysUntil day).toInt()
        // from 2024-03-11 until 2025-03-10
        val expected = downloads.filter { it.day > YearMonthDay(2024, 3, 10) && it.day <= day }.sumOf { it.downloads }
        assertEquals(BigDecimal.valueOf(expected), value(AllPluginsDailyValues.DOWNLOADS, index))
    }

    @Test
    fun monthSumsAreTheSumsOfTheMonthBeforeTheDay() = runBlocking {
        // the end of a month is the calendar month
        val endOfMonth = (values.days.first() daysUntil YearMonthDay(2024, 2, 29)).toInt()
        val february = PaymentAmountTracker(YearMonthDayRange(YearMonthDay(2024, 2, 1), YearMonthDay(2024, 2, 29)), exchangeRates)
        sales.forEach { february.add(it.date, it.amountUSD, it.amount) }
        assertEquals(amount(february.totalAmount), value(AllPluginsDailyValues.MONTH_SALES, endOfMonth))
        assertEquals(amount(february.paidAmount), value(AllPluginsDailyValues.MONTH_PAID, endOfMonth))

        // within a month, from the day after the same day of the previous month
        val day = YearMonthDay(2024, 5, 10)
        val index = (values.days.first() daysUntil day).toInt()
        val expected = downloads.filter { it.day > YearMonthDay(2024, 4, 10) && it.day <= day }.sumOf { it.downloads }
        assertEquals(BigDecimal.valueOf(expected), value(AllPluginsDailyValues.MONTH_DOWNLOADS, index))
    }

    @Test
    fun daysFromTheFirstYearUntilToday() {
        assertEquals(YearMonthDay(2023, 1, 1), values.days.first())
        assertEquals(today, values.days.last())
        assertTrue(values.series.values.all { it.values.size == values.days.size })
        // the sums of today and the recurring revenue of the current month may still change
        val sums = listOf(
            AllPluginsDailyValues.SALES, AllPluginsDailyValues.FEES, AllPluginsDailyValues.PAID, AllPluginsDailyValues.DOWNLOADS,
            AllPluginsDailyValues.MONTH_SALES, AllPluginsDailyValues.MONTH_FEES, AllPluginsDailyValues.MONTH_PAID,
            AllPluginsDailyValues.MONTH_DOWNLOADS,
        )
        assertTrue(sums.all { values.series.getValue(it).incompleteFrom == values.days.lastIndex })
        val firstOfMonth = (values.days.first() daysUntil YearMonthDay(2025, 9, 1)).toInt()
        assertEquals(firstOfMonth, values.series.getValue(AllPluginsDailyValues.MRR).incompleteFrom)
        assertEquals(firstOfMonth, values.series.getValue(AllPluginsDailyValues.ARR).incompleteFrom)
    }

    @Test
    fun trialsOfTheYearBeforeTheDay() {
        // a trial every 5 days, starting before the first sale
        val trials = (0 until 200).map { PluginTrial("trial-$it", YearMonthDay(2022, 12, 1).add(0, 0, it * 5), customer(it)) }
        val values = AllPluginsDailyValues.create(sales, downloads, emptyList(), exchangeRates, today, trials)
        assertEquals(YearMonthDay(2022, 1, 1), values.days.first())

        val series = values.series.getValue(AllPluginsDailyValues.TRIALS)
        val endOfYear = (values.days.first() daysUntil YearMonthDay(2023, 12, 31)).toInt()
        assertEquals(BigDecimal.valueOf(trials.count { it.date.year == 2023 }.toLong()), series.values[endOfYear])

        val day = YearMonthDay(2024, 3, 10)
        val expected = trials.count { it.date > YearMonthDay(2023, 3, 10) && it.date <= day }
        assertEquals(BigDecimal.valueOf(expected.toLong()), series.values[(values.days.first() daysUntil day).toInt()])

        // without trials, e.g. for the summary of all plugins
        assertFalse(this.values.series.containsKey(AllPluginsDailyValues.TRIALS))
    }

    @Test
    fun noData() {
        assertTrue(AllPluginsDailyValues.create(emptyList(), emptyList(), emptyList(), exchangeRates, today).days.isEmpty())
    }

    private fun value(id: String, index: Int): BigDecimal? = values.series.getValue(id).values[index]

    private fun amount(amount: MonetaryAmount): BigDecimal {
        return amount.number.numberValue(BigDecimal::class.java).setScale(2, RoundingMode.HALF_UP)
    }
}
