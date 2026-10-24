/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.customer
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.sale
import dev.ja.marketplace.data.trackers.PaymentAmountTracker
import dev.ja.marketplace.exchangeRate.ExchangeRates
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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

    private val values = AllPluginsDailyValues.create(sales, downloads, exchangeRates, today)

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
    fun daysFromTheFirstYearUntilToday() {
        assertEquals(YearMonthDay(2023, 1, 1), values.days.first())
        assertEquals(today, values.days.last())
        assertTrue(values.series.values.all { it.values.size == values.days.size && it.incompleteFrom == values.days.lastIndex })
    }

    @Test
    fun noData() {
        assertTrue(AllPluginsDailyValues.create(emptyList(), emptyList(), exchangeRates, today).days.isEmpty())
    }

    private fun value(id: String, index: Int): BigDecimal? = values.series.getValue(id).values[index]

    private fun amount(amount: MonetaryAmount): BigDecimal {
        return amount.number.numberValue(BigDecimal::class.java).setScale(2, RoundingMode.HALF_UP)
    }
}
