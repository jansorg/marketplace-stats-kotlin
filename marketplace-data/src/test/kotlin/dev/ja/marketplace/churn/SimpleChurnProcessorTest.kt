/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class SimpleChurnProcessorTest {
    @Test
    fun noChurn() {
        val churnDate = YearMonthDay(2023, 5, 31)
        val processor = SimpleChurnProcessor<String>(churnDate.add(0, -1, 0), churnDate, 7)
        processor.init()

        processor.processValue(1, "a1", YearMonthDayRange(YearMonthDay(2023, 4, 10), YearMonthDay(2023, 5, 9)), true)
        processor.processValue(1, "a2", YearMonthDayRange(YearMonthDay(2023, 5, 10), YearMonthDay(2023, 6, 10)), true)

        val result = processor.getResult()
        Assertions.assertEquals(0, result.churnedItemCount)
        Assertions.assertEquals(1, result.activeItemCount)
        Assertions.assertEquals(0.0, result.churnRate)
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

        val result = processor.getResult()
        Assertions.assertEquals(1, result.churnedItemCount)
        Assertions.assertEquals(1, result.activeItemCount)
        Assertions.assertEquals(0.5, result.churnRate)
    }
}