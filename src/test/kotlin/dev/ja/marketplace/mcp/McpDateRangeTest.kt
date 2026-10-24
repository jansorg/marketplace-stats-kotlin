/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class McpDateRangeTest {
    @Test
    fun missingDates() {
        val range = McpDateRange.parse(null, " ")
        assertTrue(range.isUnbounded)
        assertEquals(YearMonthDayRange.MAX, range.toRange())
    }

    @Test
    fun startAndEnd() {
        val range = McpDateRange.parse("2024-01-15", "2024-02-29")
        assertFalse(range.isUnbounded)
        assertEquals(YearMonthDayRange(YearMonthDay(2024, 1, 15), YearMonthDay(2024, 2, 29)), range.toRange())
    }

    @Test
    fun openRanges() {
        assertEquals(YearMonthDayRange(YearMonthDay(2024, 1, 1), YearMonthDay.MAX), McpDateRange.parse("2024-01-01", null).toRange())
        assertEquals(YearMonthDayRange(YearMonthDay.MIN, YearMonthDay(2024, 1, 1)), McpDateRange.parse(null, "2024-01-01").toRange())
    }

    @Test
    fun sameDay() {
        val range = McpDateRange.parse("2024-01-01", "2024-01-01")
        assertEquals(YearMonthDayRange.of(YearMonthDay(2024, 1, 1)), range.toRange())
    }

    @Test
    fun invalidDates() {
        assertThrows<IllegalArgumentException> { McpDateRange.parse("2024-1-1", null) }
        assertThrows<IllegalArgumentException> { McpDateRange.parse(null, "2024-02-30") }
        assertThrows<IllegalArgumentException> { McpDateRange.parse("yesterday", null) }
    }

    @Test
    fun startAfterEnd() {
        val e = assertThrows<IllegalArgumentException> { McpDateRange.parse("2024-02-01", "2024-01-31") }
        assertTrue(e.message!!.contains("must not be after"))
    }
}
