/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.overview

import dev.ja.marketplace.client.YearMonthDay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DailyWindowsTest {
    private val windows = DailyWindows(YearMonthDay(2023, 1, 1), YearMonthDay(2025, 3, 31))

    private fun monthStart(day: YearMonthDay): YearMonthDay = windows.days[windows.monthStarts[windows.index(day)]]
    private fun yearStart(day: YearMonthDay): YearMonthDay = windows.days[windows.yearStarts[windows.index(day)]]

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
        // the day after the same day of the previous year
        assertEquals(YearMonthDay(2024, 2, 29), yearStart(YearMonthDay(2025, 2, 28)))
        assertEquals(YearMonthDay(2023, 3, 1), yearStart(YearMonthDay(2024, 2, 29)))
    }

    @Test
    fun windowSums() {
        val perDay = windows.countsPerDay(listOf(YearMonthDay(2024, 2, 15) to 2L, YearMonthDay(2024, 3, 10) to 3L, YearMonthDay(2026, 1, 1) to 100L))
        val sums = windows.windowSums(perDay, windows.monthStarts)
        assertEquals(2L, sums[windows.index(YearMonthDay(2024, 2, 29))])
        assertEquals(5L, sums[windows.index(YearMonthDay(2024, 3, 14))])
        assertEquals(3L, sums[windows.index(YearMonthDay(2024, 3, 31))])
    }
}
