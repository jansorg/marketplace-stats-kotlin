/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.LicensePeriod
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SimpleChurnProcessorTest {
    @Test
    fun noChurn() {
        val churnDate = YearMonthDay(2023, 5, 31)
        val processor = SimpleChurnProcessor<String>(churnDate.add(0, -1, 0), churnDate, 7)
        processor.init()

        processor.processValue(1, "a1", YearMonthDayRange(YearMonthDay(2023, 4, 10), YearMonthDay(2023, 5, 9)), true)
        processor.processValue(1, "a2", YearMonthDayRange(YearMonthDay(2023, 5, 10), YearMonthDay(2023, 6, 10)), true)

        val result = processor.getResult(LicensePeriod.Annual)
        assertEquals(0, result.churnedItemCount)
        assertEquals(1, result.activeItemCount)
        assertEquals(0.0, result.churnRate)
    }

    @Test
    fun halfChurn() {
        val churnDate = YearMonthDay(2023, 5, 31)
        val processor = SimpleChurnProcessor<String>(churnDate.add(0, -1, 0), churnDate, 7)

        processor.init()
        processor.processValue(1, "churned1", YearMonthDay(2023, 3, 10).rangeTo(YearMonthDay(2023, 4, 9)), true)
        processor.processValue(
            1,
            "churned2",
            YearMonthDay(2023, 4, 10).rangeTo(YearMonthDay(2023, 4, 30)),
            true
        )
        processor.processValue(2, "a1", YearMonthDay(2023, 4, 10).rangeTo(YearMonthDay(2023, 5, 9)), true)
        processor.processValue(2, "a2", YearMonthDay(2023, 5, 10).rangeTo(YearMonthDay(2023, 6, 10)), true)

        val result = processor.getResult(LicensePeriod.Annual)
        assertEquals(1, result.churnedItemCount)
        assertEquals(1, result.activeItemCount)
        assertEquals(0.5, result.churnRate)
    }

    @Test
    fun outsideGracePeriod() {
        val churnDate = YearMonthDay(2023, 5, 31)
        val processor = SimpleChurnProcessor<String>(churnDate.add(0, -1, 0), churnDate, 7)
        processor.init()

        // valid in previous period
        processor.processValue(1, "churned1", YearMonthDay(2023, 4, 15).rangeTo(YearMonthDay(2023, 5, 15)), true)
        processor.processValue(2, "churned2", YearMonthDay(2022, 5, 30).rangeTo(YearMonthDay(2023, 5, 30)), true)

        // new license outside current period
        processor.processValue(1, "churned1", YearMonthDay(2023, 6, 8).rangeTo(YearMonthDay(2023, 8, 8)), true)
        processor.processValue(2, "churned2", YearMonthDay(2023, 5, 1).rangeTo(YearMonthDay(2023, 5, 30)), true)

        val result = processor.getResult(LicensePeriod.Annual)
        assertEquals(2, result.churnedItemCount)
        assertEquals(0, result.activeItemCount)
        assertEquals(1.0, result.churnRate)
    }
}