/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.CachingStrategy.Category
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.assertThrows

class CachingStrategyTest {
    private val today = YearMonthDay(2026, 10, 26)

    @Test
    fun recentSalesMayStillChange() {
        val strategy = CachingStrategy()
        assertEquals(Category.Recent, strategy.historyCategory(YearMonthDayRange(today.add(0, 0, -45), today), today))
        // a range, which ends within the recent days, may still change
        assertEquals(Category.Recent, strategy.historyCategory(YearMonthDayRange(today.add(0, 0, -76), today.add(0, 0, -45)), today))
        assertEquals(Category.Static, strategy.historyCategory(YearMonthDayRange(today.add(0, 0, -76), today.add(0, 0, -46)), today))
    }

    @Test
    fun maxCachedValues() {
        assertEquals(CachingStrategy.DEFAULT_MAX_CACHED_VALUES, CachingStrategy().maxCachedValues)
        assertEquals(100_000, CachingStrategy(maxCachedValues = 100_000).maxCachedValues)
        assertThrows<IllegalArgumentException> { CachingStrategy(maxCachedValues = -1) }
    }

    @Test
    fun historySettings() {
        val settings = CachingStrategy().historySettings
        assertEquals(30.minutes, settings.maxAge)
        assertEquals(45, settings.overlapDays)
        assertEquals(7.days, settings.fullFetchInterval)
        assertEquals(1.days, settings.backgroundUpdateMaxAge)
    }

    @Test
    fun automaticRefresh() {
        val strategy = CachingStrategy(autoRefreshInterval = 15.minutes)
        // the recent data is refreshed at each interval, requests use it for two more intervals
        assertEquals(15.minutes, strategy.maxAge(Category.Recent))
        assertEquals(45.minutes, strategy.expiry(Category.Recent))
        assertEquals(45.minutes, strategy.historySettings.maxAge)
        assertTrue(strategy.isRefreshDue(Category.Recent, 0.minutes))

        // daily data is refreshed, if it'd be outdated before the next refresh
        assertFalse(strategy.isRefreshDue(Category.Daily, 23.hours))
        assertTrue(strategy.isRefreshDue(Category.Daily, 1.days - 15.minutes))

        // without the automatic refresh, data expires after its maximum age
        assertEquals(30.minutes, CachingStrategy().expiry(Category.Recent))
        assertFalse(CachingStrategy().isRefreshDue(Category.Recent, 1.days))
    }
}
