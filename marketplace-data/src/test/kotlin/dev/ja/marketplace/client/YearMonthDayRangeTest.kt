/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YearMonthDayRangeTest {
    @Test
    fun overlapping() {
        val first = YearMonthDayRange(YearMonthDay(2020, 1, 1), YearMonthDay(2021, 1, 1))
        val second = YearMonthDayRange(YearMonthDay(2020, 6, 1), YearMonthDay(2021, 6, 1))

        assert(first.isIntersecting(first))
        assert(first.isIntersecting(second))

        assert(second.isIntersecting(first))
        assert(second.isIntersecting(second))
    }

    @Test
    fun overlappingSubRange() {
        val first = YearMonthDayRange(YearMonthDay(2020, 1, 1), YearMonthDay(2021, 1, 1))
        val second = YearMonthDayRange(YearMonthDay(2020, 6, 1), YearMonthDay(2020, 7, 1))

        assert(first.isIntersecting(first))
        assert(first.isIntersecting(second))

        assert(second.isIntersecting(first))
        assert(second.isIntersecting(second))
    }

    @Test
    fun notOverlapping() {
        val first = YearMonthDayRange(YearMonthDay(2020, 1, 1), YearMonthDay(2021, 1, 1))
        val second = YearMonthDayRange(YearMonthDay(2021, 6, 1), YearMonthDay(2022, 6, 1))

        assert(first.isIntersecting(first))
        assert(!first.isIntersecting(second))

        assert(!second.isIntersecting(first))
        assert(second.isIntersecting(second))
    }

    @Test
    fun contains() {
        val start = YearMonthDay(2023, 12, 1)
        val end = YearMonthDay(2023, 12, 10)
        val range = start.rangeTo(end)
        assert(start in range)
        assert(end in range)

        assert(start in start..end)
        assert(end in start..end)
    }

    @Test
    fun daysSequence() {
        assertTrue(YearMonthDay(2020, 2, 29) in YearMonthDayRange(YearMonthDay(2020, 1, 1), YearMonthDay(2021, 1, 1)))
    }

    @Test
    fun monthsUntil() {
        assertEquals(1, YearMonthDay(2020, 2, 10) monthsUntil YearMonthDay(2020, 3, 20))
    }
}