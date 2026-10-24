/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.persistentCache

import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.PluginSale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HistoryMergerTest {
    private val today = day(2025, 3, 15)

    private fun snapshot(fetchedUntil: dev.ja.marketplace.client.YearMonthDay) = HistorySnapshot<PluginSale>(emptyList(), fetchedUntil, 0, 0)

    private fun merge(previous: List<PluginSale>, fetched: List<PluginSale>, range: YearMonthDayRange): List<PluginSale> {
        return HistoryMerger.merge(previous, fetched, range, PluginSale::ref, PluginSale::date)
    }

    @Test
    fun fullRangeWithoutPreviousData() {
        assertEquals(YearMonthDayRange(Marketplace.Birthday, today), HistoryMerger.fetchRange(null, false, today, 45))
    }

    @Test
    fun fullRangeForFullFetch() {
        assertEquals(YearMonthDayRange(Marketplace.Birthday, today), HistoryMerger.fetchRange(snapshot(today), true, today, 45))
    }

    @Test
    fun incrementalRangeOverlapsPreviousFetch() {
        val range = HistoryMerger.fetchRange(snapshot(day(2025, 3, 10)), false, today, 45)
        assertEquals(YearMonthDayRange(day(2025, 1, 24), today), range)
    }

    @Test
    fun incrementalRangeWithoutOverlap() {
        val range = HistoryMerger.fetchRange(snapshot(day(2025, 3, 10)), false, today, 0)
        assertEquals(YearMonthDayRange(day(2025, 3, 10), today), range)
    }

    @Test
    fun incrementalRangeStartsAtBirthday() {
        val range = HistoryMerger.fetchRange(snapshot(Marketplace.Birthday.add(0, 0, 3)), false, today, 45)
        assertEquals(YearMonthDayRange(Marketplace.Birthday, today), range)
    }

    @Test
    fun incrementalRangeWithFetchInTheFuture() {
        // e.g. after a change of the time zone
        val range = HistoryMerger.fetchRange(snapshot(today.add(0, 0, 100)), false, today, 45)
        assertEquals(YearMonthDayRange(today, today), range)
    }

    @Test
    fun mergeAppendsNewItems() {
        val old = listOf(testSale("a", day(2025, 1, 1)), testSale("b", day(2025, 2, 1)))
        val fetched = listOf(testSale("b", day(2025, 2, 1)), testSale("c", day(2025, 3, 1)), testSale("d", day(2025, 3, 1)))

        val merged = merge(old, fetched, YearMonthDayRange(day(2025, 2, 1), today))
        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.ref })
    }

    @Test
    fun mergeReplacesChangedItems() {
        val old = listOf(testSale("a", day(2025, 1, 1)), testSale("b", day(2025, 2, 1), amountUSD = 100.0))
        val changed = testSale("b", day(2025, 2, 1), amountUSD = 80.0)

        val merged = merge(old, listOf(changed), YearMonthDayRange(day(2025, 2, 1), today))
        assertEquals(listOf(old[0], changed), merged)
    }

    @Test
    fun mergeRemovesItemsMissingInFetchedRange() {
        // e.g. a sale, which was removed after a refund
        val old = listOf(testSale("a", day(2025, 1, 1)), testSale("b", day(2025, 2, 1)), testSale("c", day(2025, 2, 2)))

        val merged = merge(old, listOf(testSale("c", day(2025, 2, 2))), YearMonthDayRange(day(2025, 2, 1), today))
        assertEquals(listOf("a", "c"), merged.map { it.ref })
    }

    @Test
    fun mergeKeepsRefundsAsSeparateItems() {
        val sale = testSale("a", day(2025, 1, 10))
        val refund = testSale("a-refund", day(2025, 2, 1), amountUSD = -100.0)

        val merged = merge(listOf(sale), listOf(sale, refund), YearMonthDayRange(day(2025, 1, 1), today))
        assertEquals(listOf(sale, refund), merged)
    }

    @Test
    fun mergeReplacesItemsWithSameIdOutsideOfRange() {
        // an item, which was moved to a later date
        val old = listOf(testSale("a", day(2025, 1, 1)), testSale("b", day(2025, 1, 2)))
        val moved = testSale("a", day(2025, 3, 1))

        val merged = merge(old, listOf(moved), YearMonthDayRange(day(2025, 3, 1), today))
        assertEquals(listOf(old[1], moved), merged)
    }

    @Test
    fun mergeKeepsDuplicatesOfFetchedItems() {
        // the result must be the same as without the cache, which doesn't remove duplicates of the API data
        val old = listOf(testSale("a", day(2025, 1, 1)), testSale("b", day(2025, 3, 1)))
        val fetched = listOf(testSale("b", day(2025, 3, 1)), testSale("b", day(2025, 3, 1)), testSale("c", day(2025, 3, 2)))

        val merged = merge(old, fetched, YearMonthDayRange(day(2025, 3, 1), today))
        assertEquals(listOf("a", "b", "b", "c"), merged.map { it.ref })
    }

    @Test
    fun mergeReplacesStoredDuplicates() {
        val old = listOf(testSale("a", day(2025, 1, 1)), testSale("a", day(2025, 1, 1)), testSale("b", day(2025, 3, 1)))
        val fetched = listOf(testSale("a", day(2025, 3, 2)))

        val merged = merge(old, fetched, YearMonthDayRange(day(2025, 3, 1), today))
        assertEquals(fetched, merged)
    }

    @Test
    fun mergeKeepsOrderOfFetchedItemsOfTheSameDay() {
        val old = listOf(testSale("x", day(2025, 1, 1)))
        val fetched = listOf(testSale("c", day(2025, 3, 1)), testSale("a", day(2025, 3, 1)), testSale("b", day(2025, 3, 1)))

        val merged = merge(old, fetched, YearMonthDayRange(day(2025, 2, 1), today))
        assertEquals(listOf("x", "c", "a", "b"), merged.map { it.ref })
    }

    @Test
    fun fullFetchReplacesAllItems() {
        val old = listOf(testSale("a", day(2020, 1, 1)), testSale("b", day(2025, 1, 1)))
        val fetched = listOf(testSale("b", day(2025, 1, 1)))

        val merged = merge(old, fetched, YearMonthDayRange(Marketplace.Birthday, today))
        assertEquals(fetched, merged)
    }

    @Test
    fun incrementalMergeEqualsFullFetch() {
        val all = (0 until 200).map { testSale("s$it", Marketplace.Birthday.add(0, 0, it * 10)) }
        val today = all.last().date
        val firstFetch = all.filter { it.date <= day(2021, 6, 1) }

        val range = HistoryMerger.fetchRange(HistorySnapshot(firstFetch, day(2021, 6, 1), 0, 0), false, today, 45)
        val fetched = all.filter { it.date in range }
        assertEquals(all, merge(firstFetch, fetched, range))
    }

    @Test
    fun incrementalMergeWithDuplicatesEqualsFullFetch() {
        // the API data contains the same items more than once, inside and outside the range of the incremental fetch
        val all = (0 until 200).flatMap {
            val sale = testSale("s$it", Marketplace.Birthday.add(0, 0, it * 10))
            if (it % 7 == 0) listOf(sale, sale) else listOf(sale)
        }
        val today = all.last().date
        val firstFetch = all.filter { it.date <= day(2021, 6, 1) }

        val range = HistoryMerger.fetchRange(HistorySnapshot(firstFetch, day(2021, 6, 1), 0, 0), false, today, 45)
        val fetched = all.filter { it.date in range }
        assertEquals(all, merge(firstFetch, fetched, range))
    }
}
