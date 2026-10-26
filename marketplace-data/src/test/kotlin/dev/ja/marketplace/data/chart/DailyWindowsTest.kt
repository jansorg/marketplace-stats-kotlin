/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.chart

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.ChartPeriod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DailyWindowsTest {
    private val windows = DailyWindows(YearMonthDay(2023, 1, 1), YearMonthDay(2025, 3, 31))

    private fun start(window: ChartWindow, day: YearMonthDay): YearMonthDay = windows.days[windows.starts(window)[windows.index(day)]]
    private fun monthStart(day: YearMonthDay): YearMonthDay = start(ChartWindow.Month, day)
    private fun yearStart(day: YearMonthDay): YearMonthDay = start(ChartWindow.Year, day)

    @Test
    fun monthBeforeEachDay() {
        // the last day of a month is the calendar month, also of short months
        assertEquals(YearMonthDay(2024, 2, 1), monthStart(YearMonthDay(2024, 2, 29)))
        assertEquals(YearMonthDay(2025, 3, 1), monthStart(YearMonthDay(2025, 3, 31)))
        // from the day after the same day of the previous month, at most until the end of the previous month
        assertEquals(YearMonthDay(2024, 3, 1), monthStart(YearMonthDay(2024, 3, 30)))
        assertEquals(YearMonthDay(2024, 2, 16), monthStart(YearMonthDay(2024, 3, 15)))
        // the first days have no previous month
        assertEquals(YearMonthDay(2023, 1, 1), monthStart(YearMonthDay(2023, 1, 20)))
    }

    @Test
    fun yearBeforeEachDay() {
        assertEquals(YearMonthDay(2024, 1, 1), yearStart(YearMonthDay(2024, 12, 31)))
        // the 12 calendar months at the end of a month
        assertEquals(YearMonthDay(2024, 3, 1), yearStart(YearMonthDay(2025, 2, 28)))
        assertEquals(YearMonthDay(2023, 3, 1), yearStart(YearMonthDay(2024, 2, 29)))
        // the day after the same day of the previous year
        assertEquals(YearMonthDay(2024, 3, 16), yearStart(YearMonthDay(2025, 3, 15)))
    }

    @Test
    fun otherWindows() {
        assertEquals(YearMonthDay(2024, 3, 15), start(ChartWindow.Day, YearMonthDay(2024, 3, 15)))
        // a week at a Sunday is the calendar week
        assertEquals(YearMonthDay(2024, 3, 11), start(ChartWindow.Week, YearMonthDay(2024, 3, 17)))
        // a quarter at its last day is the calendar quarter
        assertEquals(YearMonthDay(2024, 1, 1), start(ChartWindow.Quarter, YearMonthDay(2024, 3, 31)))
        assertEquals(YearMonthDay(2023, 12, 16), start(ChartWindow.Quarter, YearMonthDay(2024, 3, 15)))
    }

    @Test
    fun calendarWindows() {
        // from the first day of the calendar period until the day
        assertEquals(YearMonthDay(2024, 3, 1), start(ChartWindow.CalendarMonth, YearMonthDay(2024, 3, 15)))
        assertEquals(YearMonthDay(2024, 1, 1), start(ChartWindow.CalendarQuarter, YearMonthDay(2024, 3, 15)))
        assertEquals(YearMonthDay(2024, 1, 1), start(ChartWindow.CalendarYear, YearMonthDay(2024, 11, 30)))
        // the tables of months and years open their charts with the calendar windows
        assertEquals(ChartLevel(ChartWindow.CalendarMonth, ChartDetail.Month), ChartLevel.of(ChartPeriod.Month))
        assertEquals(ChartLevel(ChartWindow.CalendarYear, ChartDetail.Year), ChartLevel.of(ChartPeriod.Year))
    }

    @Test
    fun currentPeriods() {
        val today = YearMonthDay(2025, 8, 20)
        assertEquals(today, ChartWindow.Day.currentPeriodStart(today))
        assertEquals(YearMonthDay(2025, 8, 18), ChartWindow.Week.currentPeriodStart(today))
        assertEquals(YearMonthDay(2025, 8, 1), ChartWindow.Month.currentPeriodStart(today))
        assertEquals(YearMonthDay(2025, 7, 1), ChartWindow.Quarter.currentPeriodStart(today))
        assertEquals(YearMonthDay(2025, 1, 1), ChartWindow.Year.currentPeriodStart(today))
        assertEquals(YearMonthDay(2025, 8, 1), ChartWindow.CalendarMonth.currentPeriodStart(today))
    }

    @Test
    fun detailLevels() {
        val days = List(70) { YearMonthDay(2024, 12, 1).add(0, 0, it) }
        val dates = { detail: ChartDetail -> detail.indices(days).map { days[it] } }
        assertEquals(days, dates(ChartDetail.Day))
        assertEquals(listOf(YearMonthDay(2024, 12, 31), days.last()), dates(ChartDetail.Year))
        assertEquals(listOf(YearMonthDay(2024, 12, 31), YearMonthDay(2025, 1, 31), days.last()), dates(ChartDetail.Month))
        // the Sundays and the last day
        assertEquals(YearMonthDay(2024, 12, 1), dates(ChartDetail.Week).first())
        assertEquals(11, dates(ChartDetail.Week).size)
    }

    @Test
    fun windowSums() {
        val perDay = windows.countsPerDay(listOf(YearMonthDay(2024, 2, 15) to 2L, YearMonthDay(2024, 3, 10) to 3L, YearMonthDay(2026, 1, 1) to 100L))
        val sums = windows.windowSums(perDay, windows.starts(ChartWindow.Month))
        assertEquals(2L, sums[windows.index(YearMonthDay(2024, 2, 29))])
        assertEquals(5L, sums[windows.index(YearMonthDay(2024, 3, 14))])
        assertEquals(3L, sums[windows.index(YearMonthDay(2024, 3, 31))])
    }
}
