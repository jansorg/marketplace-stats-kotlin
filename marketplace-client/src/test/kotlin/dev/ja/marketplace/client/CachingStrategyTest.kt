/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.CachingStrategy.Category
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class CachingStrategyTest {
    private val today = YearMonthDay(2026, 10, 26)

    @Test
    fun recentSalesMayStillChange() {
        val strategy = CachingStrategy()
        assertEquals(Category.Recent, strategy.historyCategory(YearMonthDayRange(today.add(0, 0, -45), today), today))
        assertEquals(Category.Static, strategy.historyCategory(YearMonthDayRange(today.add(0, 0, -46), today.add(0, 0, -16)), today))
    }

    @Test
    fun historySettings() {
        val settings = CachingStrategy().historySettings
        assertEquals(30.minutes, settings.maxAge)
        assertEquals(45, settings.overlapDays)
        assertEquals(7.days, settings.fullFetchInterval)
        assertEquals(1.days, settings.backgroundUpdateMaxAge)
    }
}
