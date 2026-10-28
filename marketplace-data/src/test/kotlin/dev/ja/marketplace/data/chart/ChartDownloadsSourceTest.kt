/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.chart

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.client.model.DownloadDimension
import dev.ja.marketplace.client.model.DownloadFilter
import dev.ja.marketplace.client.model.DownloadRequestDimension
import dev.ja.marketplace.client.model.DownloadResponse
import dev.ja.marketplace.client.model.DownloadResponseData
import dev.ja.marketplace.client.model.DownloadResponseItem
import dev.ja.marketplace.client.model.PluginReleaseInfo
import dev.ja.marketplace.client.model.DownloadResponseGroup
import dev.ja.marketplace.client.model.GroupedDownloadResponse
import dev.ja.marketplace.services.BuildNumberRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.time.Instant

class ChartDownloadsSourceTest {
    /** The requested dimensions and ranges, e.g. `Month 2025-01-01..2025-12-31`. */
    private val requests = mutableListOf<String>()

    /** The release 1001, published in the middle of the month before the previous month. */
    private val releaseDate = YearMonthDay.now().add(0, -2, 0).let { YearMonthDay(it.year, it.month, 20) }

    private val release = PluginReleaseInfo(
        id = 1001, pluginId = 1, updateInfoUrlPath = "", version = "1.0", approve = true, listed = true, hidden = false,
        createdTimestamp = Instant.parse("${releaseDate.asIsoString}T12:00:00Z"), fileUrlPath = "", fileSizeBytes = 0,
        channel = "", downloads = 0,
    )

    /** The downloads of February 2025 and of the current month. */
    private val client = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MarketplaceClient::class.java)) { _, method, args ->
        when (method.name) {
            "downloads" -> {
                val dimension = args[1] as DownloadRequestDimension
                val start = args[3] as YearMonthDay
                val end = args[5] as YearMonthDay?
                requests += "$dimension $start..$end"
                val today = YearMonthDay.now()
                val months = listOf(YearMonthDay(2025, 2, 1) to 5L, YearMonthDay.firstOfMonth(today.year, today.month) to 7L)
                    .filter { (month, _) -> month.year == start.year }
                DownloadResponse("downloads-count", (args[4] as Array<*>).map { it as DownloadFilter }, DownloadDimension.Month,
                    DownloadResponseData(DownloadDimension.Month, months.map { (month, value) -> DownloadResponseItem(month.asIsoString, value) }))
            }

            "channels" -> emptyList<String>()
            "releases" -> if (args[1] == "") listOf(release) else emptyList()
            else -> throw UnsupportedOperationException(method.name)
        }
    } as MarketplaceClient

    @Test
    suspend fun uniqueDownloadsAreRequestedInWholePeriods() {
        val today = YearMonthDay.now()
        val start = today.add(0, -2, 0)
        val source = ChartDownloadsSource.of(client, 1)
        for (period in listOf(DownloadRequestDimension.Day, DownloadRequestDimension.Week, DownloadRequestDimension.Month)) {
            source.uniqueDownloads(null, "IIU", start, period)
        }
        val ranges = requests.map { it.split(" ") }.groupBy({ it[0] }, { it[1].split("..").let { (from, to) -> YearMonthDay.parse(from) to to.takeUnless { it == "null" }?.let(YearMonthDay::parse) } })

        // the half months by day, the blocks of 4 weeks from Monday, the calendar years by month, the last one until today
        assertTrue(setOf(1, 16).containsAll(ranges.getValue("Day").map { it.first.day }))
        assertEquals(setOf(java.time.DayOfWeek.MONDAY), ranges.getValue("Week").map { it.first.toLocalDate().dayOfWeek }.toSet())
        ranges.getValue("Week").dropLast(1).forEach { (from, to) -> assertEquals(from.add(0, 0, 27), to) }
        assertEquals(setOf(1 to 1), ranges.getValue("Month").map { it.first.month to it.first.day }.toSet())
        listOf("Day", "Week", "Month").forEach { assertEquals(null, ranges.getValue(it).maxBy { range -> range.first }.second) }
    }

    @Test
    suspend fun theDownloadsOfAReleaseAreRequestedFromItsReleaseDate() {
        val source = ChartDownloadsSource.of(client, 1)
        val start = releaseDate.add(-1, 0, 0)
        source.downloads(1001, "IIU", start)
        source.uniqueDownloads(1001, null, start, DownloadRequestDimension.Day)

        // the half months by day from the half of the release date, there are no downloads before it
        val firstDays = requests.map { YearMonthDay.parse(it.substringAfter(" ").substringBefore("..")) }
        assertEquals(YearMonthDay(releaseDate.year, releaseDate.month, 16), firstDays.min())
        assertEquals(2 * firstDays.distinct().size, requests.size)

        // a later start of the shown range is used
        requests.clear()
        source.downloads(1001, "IIU", YearMonthDay.now())
        assertTrue(requests.all { YearMonthDay.parse(it.substringAfter(" ").substringBefore("..")) > releaseDate })

        // the plugin's downloads aren't limited by a release
        requests.clear()
        source.downloads(null, "IIU", start)
        assertEquals(YearMonthDay(start.year, start.month, if (start.day >= 16) 16 else 1), requests.minOf { YearMonthDay.parse(it.substringAfter(" ").substringBefore("..")) })
    }

    @Test
    suspend fun downloadsOfEachMonthAreCountedAtItsEnd() {
        val today = YearMonthDay.now()
        val downloads = ChartDownloadsSource.of(client, 1).downloads(null, "IIU", YearMonthDay(today.year - 1, 3, 5), byMonth = true)

        // a request of each calendar year by month, the current year until today: the statistics service only counts
        // whole calendar months, a request by month must not start or end within a month
        assertEquals(listOf("Month ${today.year - 1}-01-01..${today.year - 1}-12-31", "Month ${today.year}-01-01..null"), requests.sorted())
        // the downloads of a month at its last day, of the current month at today
        val expected = listOfNotNull(
            DailyDownload(YearMonthDay(2025, 2, 28), 5).takeIf { today.year - 1 == 2025 },
            DailyDownload(today, 7),
        )
        assertEquals(expected, downloads)
    }

    @Test
    suspend fun theDownloadsForAnIdeVersionAreTheDownloadsOfItsReleases() {
        val grouped = mutableListOf<String>()
        fun release(id: Int, created: String, since: String, until: String?) = PluginReleaseInfo(
            id = id, pluginId = 1, updateInfoUrlPath = "", version = "1.$id", approve = true, listed = true, hidden = false,
            createdTimestamp = Instant.parse("${created}T12:00:00Z"), fileUrlPath = "", fileSizeBytes = 0, channel = "", downloads = 0,
            since = BuildNumberRef.of(since), until = until?.let(BuildNumberRef::of),
        )
        // two releases for 2026.2, one for 2026.1, one for 2026.1 and 2026.2, one for 2026.1 and later IDEs
        val releases = listOf(
            release(2001, "2026-07-10", "262.1", "262.*"),
            release(2002, "2026-08-01", "262.4852.0", "262.*"),
            release(2003, "2026-06-01", "261.1", "262.*"),
            release(2004, "2026-05-01", "261.1", null),
            release(2005, "2026-04-01", "261.22158.0", "261.*"),
        )
        val client = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MarketplaceClient::class.java)) { _, method, args ->
            when (method.name) {
                "channels" -> emptyList<String>()
                "releases" -> if (args[1] == "") releases else emptyList()
                "groupedDownloads" -> {
                    val dimension = args[1] as DownloadRequestDimension
                    grouped += "$dimension ${args[2]} ${args[4]}"
                    val days = if (dimension == DownloadRequestDimension.Month) listOf("2026-07-01", "2026-08-01") else listOf("2026-08-03", "2026-08-04")
                    GroupedDownloadResponse("downloads-count", dimension.requestPathSegment, "update", releases.map { release ->
                        DownloadResponseGroup(release.id.toString(), null, days.map { DownloadResponseItem(it, release.id - 2000L) })
                    })
                }

                else -> throw UnsupportedOperationException(method.name)
            }
        } as MarketplaceClient
        val source = ChartDownloadsSource.of(client, 1)

        assertEquals(listOf("262", "261"), source.majorVersions().map { it.value })
        assertEquals(listOf("2026.2", "2026.1"), source.majorVersions().map { it.label })
        // the sums of the releases 2001 and 2002 from the first release for the IDE version
        val downloads = source.majorVersionDownloads(262, YearMonthDay(2026, 1, 1))
        assertEquals(listOf(DailyDownload(YearMonthDay(2026, 8, 3), 3), DailyDownload(YearMonthDay(2026, 8, 4), 3)), downloads)
        assertEquals(listOf("Day Update 2026-07-10"), grouped)

        // by month from the first day of the month, the downloads of a month at its last day
        grouped.clear()
        val months = source.majorVersionDownloads(262, YearMonthDay(2026, 1, 1), byMonth = true)
        assertEquals(listOf("Month Update 2026-07-01"), grouped)
        assertEquals(listOf(YearMonthDay(2026, 7, 31), YearMonthDay(2026, 8, 31)), months.map { it.day })
        assertEquals(emptyList<DailyDownload>(), source.majorVersionDownloads(243, YearMonthDay(2026, 1, 1)))
    }
}
