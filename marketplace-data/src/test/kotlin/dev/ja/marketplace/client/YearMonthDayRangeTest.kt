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

    @Test
    fun singleDayStepSequence() {
        val day = YearMonthDay(2021, 10, 9)
        assertEquals(listOf(day rangeTo day), (day rangeTo day).stepSequence(0, 0, 1).toList())
        assertEquals(listOf(day rangeTo day), (day rangeTo day).stepSequence(0, 0, 2).toList())
    }

    @Test
    fun daysStepSequence() {
        val range = YearMonthDay(2020, 12, 9) rangeTo YearMonthDay(2020, 12, 12)
        assertEquals(
            listOf(
                YearMonthDay(2020, 12, 9) rangeTo YearMonthDay(2020, 12, 9),
                YearMonthDay(2020, 12, 10) rangeTo YearMonthDay(2020, 12, 10),
                YearMonthDay(2020, 12, 11) rangeTo YearMonthDay(2020, 12, 11),
                YearMonthDay(2020, 12, 12) rangeTo YearMonthDay(2020, 12, 12),
            ),
            range.stepSequence(days = 1).toList()
        )
    }

    @Test
    fun monthsSequence() {
        val range = YearMonthDay(2020, 12, 9) rangeTo YearMonthDay(2021, 2, 11)
        assertEquals(
            listOf(
                YearMonthDay(2020, 12, 9) rangeTo YearMonthDay(2021, 1, 8),
                YearMonthDay(2021, 1, 9) rangeTo YearMonthDay(2021, 2, 8),
                YearMonthDay(2021, 2, 9) rangeTo YearMonthDay(2021, 2, 11),
            ),
            range.stepSequence(months = 1).toList()
        )
    }
}