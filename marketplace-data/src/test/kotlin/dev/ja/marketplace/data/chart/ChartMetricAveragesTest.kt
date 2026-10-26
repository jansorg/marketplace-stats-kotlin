/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.chart

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.customer
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.licenses
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.sale
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ChartMetricAveragesTest {
    private val today = YearMonthDay(2025, 9, 17)

    // a download each day of 2024, 2 at each day of 2025
    private val downloads = (0..625).map { YearMonthDay(2024, 1, 1).add(0, 0, it) }.map { DailyDownload(it, if (it.year == 2024) 1L else 2L) }

    // a trial at the first day of each month, the trials of each second month are converted
    private val trials = (0 until 20).map { PluginTrial("trial-$it", YearMonthDay(2024, 1, 1).add(0, it, 0), customer(it)) }
    private val sales = trials.filterIndexed { index, _ -> index % 2 == 0 }.map { sale(it.customer, "license-${it.referenceId}", it.date.add(0, 0, 3), 10) }

    private val values = ChartMetricValues.create(sales, licenses(*sales.toTypedArray()), trials, downloads, ExchangeRates("USD"), today)

    private fun value(series: DailySeries?, day: YearMonthDay): BigDecimal? = series!!.values[values.days.indexOf(day)]

    @Test
    fun averageOfSumsIsTheSumOfTheWindowsDividedByTheirNumber() {
        // the average month of January until March 2025 with 2 downloads per day: (62 + 56 + 62) / 3
        val march = YearMonthDay(2025, 3, 31)
        val expected = BigDecimal("60.00")
        assertEquals(expected, value(values.average(ChartMetric.Downloads, ChartWindow.Month, 3), march))
        // the calendar month uses the rolling month
        assertEquals(expected, value(values.average(ChartMetric.Downloads, ChartWindow.CalendarMonth, 3), march))

        // the average day of the month before the day
        assertEquals(BigDecimal("2.00"), value(values.average(ChartMetric.Downloads, ChartWindow.Day, 1), YearMonthDay(2025, 3, 15)))
        // the average day of the 12 months before the end of 2024
        assertEquals(BigDecimal("1.00"), value(values.average(ChartMetric.Downloads, ChartWindow.Day, 12), YearMonthDay(2024, 12, 31)))
    }

    @Test
    fun averageOfRatesPoolsTheWindows() {
        // the conversion of the trials of 3 months: the trials of 2 months of 3 are converted, or 1 of 3
        val conversion = values.average(ChartMetric.TrialConversion, ChartWindow.Month, 3)
        assertEquals(BigDecimal("66.67"), value(conversion, YearMonthDay(2024, 3, 31)))
        assertEquals(BigDecimal("33.33"), value(conversion, YearMonthDay(2024, 4, 30)))
        // the conversion of the trials of a year
        assertEquals(BigDecimal("50.00"), value(values.average(ChartMetric.TrialConversion, ChartWindow.Month, 12), YearMonthDay(2024, 12, 31)))
    }

    @Test
    fun averageOfValuesAtEachDayIsTheAverageOfTheDays() {
        val licenses = values.series(ChartMetric.Licenses, ChartWindow.Month)!!
        val day = YearMonthDay(2024, 6, 30)
        val index = values.days.indexOf(day)
        val first = values.days.indexOf(YearMonthDay(2024, 6, 1))
        val expected = licenses.values.subList(first, index + 1).fold(BigDecimal.ZERO) { a, b -> a + b!! }
            .divide(BigDecimal(index - first + 1), 2, java.math.RoundingMode.HALF_UP)
        assertEquals(expected, value(values.average(ChartMetric.Licenses, ChartWindow.Year, 1), day))
    }

    @Test
    fun averagesOfAllWindows() {
        assertTrue(ChartMetricValues.averageApplies(ChartMetric.Downloads, ChartWindow.Day, 1))
        assertTrue(ChartMetricValues.averageApplies(ChartMetric.Downloads, ChartWindow.Year, 3))
        assertFalse(ChartMetricValues.averageApplies(ChartMetric.Downloads, ChartWindow.Month, 2))
        // a window, which isn't shorter than the months, is the average of a single window, its value
        val day = YearMonthDay(2025, 3, 31)
        assertEquals(value(values.series(ChartMetric.Downloads, ChartWindow.Year), day)!!.setScale(2),
            value(values.average(ChartMetric.Downloads, ChartWindow.Year, 3), day))
        assertEquals(value(values.series(ChartMetric.Downloads, ChartWindow.Month), day)!!.setScale(2),
            value(values.average(ChartMetric.Downloads, ChartWindow.Month, 1), day))
        // no average before the first day
        assertNull(value(values.average(ChartMetric.Downloads, ChartWindow.Month, 12), YearMonthDay(2024, 6, 30)))
    }
}
