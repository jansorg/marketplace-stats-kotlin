/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.model.DownloadCountType
import dev.ja.marketplace.client.model.DownloadRequestDimension
import dev.ja.marketplace.client.model.DownloadResponseItem
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The statistics are requested in parts, if the statistics service answers with 503 because they take too long.
 */
class StatisticsInChunksTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    /**
     * Downloads since March 2024, a value of the first day of each requested range. Without a start date, the
     * statistics service needs too long for the daily downloads and answers with 503.
     */
    private fun createClient(monthlyStatus: HttpStatusCode = HttpStatusCode.OK): KtorMarketplaceClient {
        val engine = MockEngine { request ->
            requests += request
            val start = request.url.parameters["startDate"]
            val dimension = request.url.segments.last()
            when {
                dimension == "month" && monthlyStatus != HttpStatusCode.OK -> respond("", monthlyStatus)
                dimension == "month" -> respond(
                    """{"measure": "downloads-count", "filters": [], "dim1": "month", "data": {"dimension": "month",
                        "serie": [{"name": "2024-03-01", "value": 3}, {"name": "2024-04-01", "value": 4}]}}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                )

                start == null -> respond("", HttpStatusCode.ServiceUnavailable)
                else -> respond(
                    """{"measure": "downloads-count", "filters": [], "dim1": "day", "data": {"dimension": "day",
                        "serie": [{"name": "$start", "value": 1}]}}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        return KtorMarketplaceClient(httpClientBuilder = mockHttpClientBuilder(engine))
    }

    @Test
    fun weeklyChunksOfDailyStatistics() {
        assertEquals(
            // calendar weeks from Monday to Sunday, the first starts at a Wednesday
            listOf(
                YearMonthDay(2026, 7, 29) to YearMonthDay(2026, 8, 2),
                YearMonthDay(2026, 8, 3) to YearMonthDay(2026, 8, 9),
                YearMonthDay(2026, 8, 10) to YearMonthDay(2026, 8, 14),
            ),
            statisticsChunks(YearMonthDay(2026, 7, 29), YearMonthDay(2026, 8, 14), DownloadRequestDimension.Day, untilToday = false),
        )
        // the last chunk ends today, without an end date, the first starts at a Saturday
        assertEquals(
            listOf(YearMonthDay(2026, 8, 1) to YearMonthDay(2026, 8, 2), YearMonthDay(2026, 8, 3) to YearMonthDay(2026, 8, 9), YearMonthDay(2026, 8, 10) to null),
            statisticsChunks(YearMonthDay(2026, 8, 1), YearMonthDay(2026, 8, 10), DownloadRequestDimension.Day, untilToday = true),
        )
        assertEquals(
            listOf(YearMonthDay(2026, 8, 1) to null),
            statisticsChunks(YearMonthDay(2026, 8, 1), YearMonthDay(2026, 8, 1), DownloadRequestDimension.Day, untilToday = true),
        )
    }

    @Test
    fun monthlyChunksOfMonthlyStatistics() {
        assertEquals(
            listOf(
                YearMonthDay(2025, 12, 15) to YearMonthDay(2025, 12, 31),
                YearMonthDay(2026, 1, 1) to YearMonthDay(2026, 1, 31),
                YearMonthDay(2026, 2, 1) to YearMonthDay(2026, 2, 10),
            ),
            statisticsChunks(YearMonthDay(2025, 12, 15), YearMonthDay(2026, 2, 10), DownloadRequestDimension.Month, untilToday = false),
        )
        assertEquals(
            listOf(YearMonthDay(2026, 1, 1) to YearMonthDay(2026, 1, 31), YearMonthDay(2026, 2, 1) to null),
            statisticsChunks(YearMonthDay(2026, 1, 1), YearMonthDay(2026, 2, 10), DownloadRequestDimension.Month, untilToday = true),
        )
    }

    @Test
    fun splitStatisticsRange() {
        assertEquals(
            (YearMonthDay(2026, 8, 1) to YearMonthDay(2026, 8, 3)) to (YearMonthDay(2026, 8, 4) to YearMonthDay(2026, 8, 7)),
            splitStatisticsRange(YearMonthDay(2026, 8, 1), YearMonthDay(2026, 8, 7), DownloadRequestDimension.Day),
        )
        assertEquals(
            (YearMonthDay(2026, 8, 1) to YearMonthDay(2026, 8, 1)) to (YearMonthDay(2026, 8, 2) to YearMonthDay(2026, 8, 2)),
            splitStatisticsRange(YearMonthDay(2026, 8, 1), YearMonthDay(2026, 8, 2), DownloadRequestDimension.Day),
        )
        assertNull(splitStatisticsRange(YearMonthDay(2026, 8, 1), YearMonthDay(2026, 8, 1), DownloadRequestDimension.Day))

        // by month at the first day of a month
        assertEquals(
            (YearMonthDay(2026, 7, 29) to YearMonthDay(2026, 8, 31)) to (YearMonthDay(2026, 9, 1) to YearMonthDay(2026, 10, 28)),
            splitStatisticsRange(YearMonthDay(2026, 7, 29), YearMonthDay(2026, 10, 28), DownloadRequestDimension.Month),
        )
        // the second half keeps the open end
        assertNull(splitStatisticsRange(YearMonthDay(2020, 1, 1), null, DownloadRequestDimension.Month)!!.second.second)
        assertNull(splitStatisticsRange(YearMonthDay(2026, 8, 5), YearMonthDay(2026, 8, 31), DownloadRequestDimension.Month))
    }

    @Test
    fun mergeSeries() {
        val merged = mergeSeries(listOf(
            listOf(DownloadResponseItem("2025-12-01", 2), DownloadResponseItem("2025-11-01", 1)),
            listOf(DownloadResponseItem("2026-01-01", 3), DownloadResponseItem("2025-12-01", 4)),
        ))
        assertEquals(listOf("2025-11-01" to 1L, "2025-12-01" to 6L, "2026-01-01" to 3L), merged.map { it.name to it.value })
    }

    @Test
    suspend fun dailyDownloadsAreRequestedInHalvesAfterA503() {
        val serie = createClient().downloads(1, DownloadRequestDimension.Day, DownloadCountType.Downloads, null).data.serie

        // the single request, the first month of the monthly downloads, then a request of each half
        val daily = requests.filter { it.url.segments.last() == "day" }.map { it.url.parameters["startDate"] to it.url.parameters["endDate"] }
        val (first, second) = splitStatisticsRange(YearMonthDay(2024, 3, 1), null, DownloadRequestDimension.Day)!!
        val halves = listOf(first, second).map { (start, end) -> start.asIsoString to end?.asIsoString }
        assertEquals(listOf(null to null) + halves, listOf(daily.first()) + daily.drop(1).sortedBy { it.first })
        assertEquals(halves.map { it.first }, serie.map { it.name })
    }

    @Test
    suspend fun groupedDailyDownloadsAreRequestedByWeekAndSplitAfterA503() {
        // the service answers ranges of more than 3 days with 503
        val engine = MockEngine { request ->
            requests += request
            val start = YearMonthDay.parse(request.url.parameters["startDate"]!!)
            val end = YearMonthDay.parse(request.url.parameters["endDate"]!!)
            when {
                start daysUntil end >= 3 -> respond("", HttpStatusCode.ServiceUnavailable)
                else -> respond(
                    """{"measure": "downloads-count", "filters": [], "dim1": "day", "dim2": "product_code", "data": [
                        {"dimension": "IU", "dimensionComment": "IntelliJ IDEA Ultimate", "serie": [{"name": "$start", "value": 1}]}]}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val client = KtorMarketplaceClient(httpClientBuilder = mockHttpClientBuilder(engine))
        val response = client.groupedDownloads(
            1, DownloadRequestDimension.Day, DownloadRequestDimension.ProductCode, DownloadCountType.Downloads,
            // the weeks from Monday to Sunday
            YearMonthDay(2026, 8, 3), endDate = YearMonthDay(2026, 8, 16),
        )

        val loaded = listOf(3 to 5, 6 to 7, 8 to 9, 10 to 12, 13 to 14, 15 to 16)
        assertEquals(
            listOf(3 to 9, 3 to 5, 6 to 9, 6 to 7, 8 to 9, 10 to 16, 10 to 12, 13 to 16, 13 to 14, 15 to 16).sortedWith(compareBy({ it.first }, { it.second })),
            requests.map { YearMonthDay.parse(it.url.parameters["startDate"]!!).day to YearMonthDay.parse(it.url.parameters["endDate"]!!).day }
                .sortedWith(compareBy({ it.first }, { it.second })),
        )
        val group = response.groups.single()
        assertEquals("IU", group.name)
        assertEquals("IntelliJ IDEA Ultimate", group.comment)
        assertEquals(loaded.map { YearMonthDay(2026, 8, it.first).asIsoString }, group.serie.map { it.name })
    }

    @Test
    suspend fun dailyDownloadsOfAShortTimeRange() {
        createClient().downloads(1, DownloadRequestDimension.Day, DownloadCountType.Downloads, YearMonthDay(2025, 1, 1), endDate = YearMonthDay(2025, 1, 31))
        assertEquals(1, requests.size)
    }

    @Test
    suspend fun monthlyDownloadsWithoutStartCantBeSplit() {
        // they're the first month of the downloads, the 503 fails the request
        val client = createClient(monthlyStatus = HttpStatusCode.ServiceUnavailable)
        assertThrows<ServerResponseException> { client.downloads(1, DownloadRequestDimension.Month, DownloadCountType.Downloads, null) }
        assertEquals(1, requests.size)
    }
}
