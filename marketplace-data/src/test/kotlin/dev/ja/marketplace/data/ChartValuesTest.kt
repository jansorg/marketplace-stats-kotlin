/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.YearMonthDay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChartValuesTest {
    private val today = YearMonthDay(2026, 10, 23)

    @Test
    fun days() {
        assertEquals("2026-10-20", ChartValues.xValue(ChartPeriod.Day, YearMonthDay(2026, 10, 20), today))
        assertEquals("2026-10-23", ChartValues.xValue(ChartPeriod.Day, today, today))
    }

    @Test
    fun months() {
        // past months at their last day
        assertEquals("2026-09-30", ChartValues.xValue(ChartPeriod.Month, "2026-09", today))
        assertEquals("2024-02-29", ChartValues.xValue(ChartPeriod.Month, "2024-02", today))
        assertEquals("2026-09-30", ChartValues.xValue(ChartPeriod.Month, YearMonthDay(2026, 9, 1), today))
        // the current month at today
        assertEquals("2026-10-23", ChartValues.xValue(ChartPeriod.Month, "2026-10", today))
    }

    @Test
    fun years() {
        // past years at the end of the year
        assertEquals("2025-12-31", ChartValues.xValue(ChartPeriod.Year, 2025, today))
        assertEquals("2024-12-31", ChartValues.xValue(ChartPeriod.Year, 2024, today))
        // the current year at today
        assertEquals("2026-10-23", ChartValues.xValue(ChartPeriod.Year, 2026, today))
    }

    @Test
    fun invalidValues() {
        assertNull(ChartValues.xValue(ChartPeriod.Month, "Total", today))
        assertNull(ChartValues.xValue(ChartPeriod.Year, 12, today))
        assertNull(ChartValues.xValue(ChartPeriod.Day, null, today))
    }
}
