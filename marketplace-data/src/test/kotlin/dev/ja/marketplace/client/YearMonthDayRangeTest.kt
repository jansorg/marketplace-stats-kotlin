/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

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
}