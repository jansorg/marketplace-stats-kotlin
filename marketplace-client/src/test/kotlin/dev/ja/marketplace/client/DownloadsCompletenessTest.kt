/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.CachingStrategy.Category
import dev.ja.marketplace.client.model.DownloadResponseItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DownloadsCompletenessTest {
    private val today = YearMonthDay(2026, 3, 10)
    private val yesterday = YearMonthDay(2026, 3, 9)
    private val completeness = DownloadsCompleteness { today }

    @Test
    fun downloadsAreRecentUntilYesterdayIsPublished() {
        // the downloads of yesterday aren't published yet
        completeness.recordDays(1, mapOf(YearMonthDay(2026, 3, 8) to 20L, yesterday to 0L), fetchedMillis = 1_000)
        assertEquals(Category.Recent, completeness.category(1, fetchedMillis = 1_000))

        // published later, the responses fetched before are still recent, the later responses are complete
        completeness.recordDays(1, mapOf(yesterday to 15L), fetchedMillis = 5_000)
        assertEquals(Category.Recent, completeness.category(1, fetchedMillis = 1_000))
        assertEquals(Category.Daily, completeness.category(1, fetchedMillis = 5_000))
        assertEquals(Category.Daily, completeness.category(1, fetchedMillis = 6_000))

        // another plugin
        assertEquals(Category.Recent, completeness.category(2, fetchedMillis = 6_000))
    }

    @Test
    fun theEarliestResponseWithTheDownloadsOfYesterdayIsKept() {
        completeness.recordDays(1, mapOf(yesterday to 15L), fetchedMillis = 5_000)
        completeness.recordDays(1, mapOf(yesterday to 16L), fetchedMillis = 8_000)
        completeness.recordDays(1, mapOf(yesterday to 16L), fetchedMillis = 3_000)
        assertEquals(Category.Daily, completeness.category(1, fetchedMillis = 3_000))
        assertEquals(Category.Recent, completeness.category(1, fetchedMillis = 2_000))
    }

    @Test
    fun publishedDaysOfThePastAreOutdated() {
        // published on an earlier day, today the downloads of yesterday are missing again
        val earlier = DownloadsCompleteness { yesterday }
        earlier.recordDays(1, mapOf(YearMonthDay(2026, 3, 8) to 10L), fetchedMillis = 1_000)
        assertEquals(Category.Daily, earlier.category(1, fetchedMillis = 1_000))

        completeness.recordDays(1, mapOf(YearMonthDay(2026, 3, 8) to 10L), fetchedMillis = 1_000)
        assertEquals(Category.Recent, completeness.category(1, fetchedMillis = 2_000))
    }

    @Test
    fun responsesBeforeYesterdayDontChange() {
        assertEquals(Category.Daily, completeness.category(1, fetchedMillis = 1_000, endDate = YearMonthDay(2026, 3, 8)))
        assertEquals(Category.Recent, completeness.category(1, fetchedMillis = 1_000, endDate = yesterday))
    }

    @Test
    fun daysOfSeries() {
        val days = DownloadsCompleteness.days(
            listOf(
                DownloadResponseItem("2026-03-09", 5),
                DownloadResponseItem("2026-03-09", 3),
                DownloadResponseItem("unknown", 7),
            )
        )
        assertEquals(mapOf(yesterday to 8L), days)
    }
}
