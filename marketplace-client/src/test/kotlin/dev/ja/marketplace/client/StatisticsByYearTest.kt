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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The statistics of long time ranges are requested by calendar year, if the statistics service answers with 503.
 */
class StatisticsByYearTest {
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
    fun calendarYearRanges() {
        assertEquals(
            listOf(
                YearMonthDay(2024, 3, 15) to YearMonthDay(2024, 12, 31),
                YearMonthDay(2025, 1, 1) to YearMonthDay(2025, 12, 31),
                YearMonthDay(2026, 1, 1) to YearMonthDay(2026, 2, 10),
            ),
            calendarYearRanges(YearMonthDay(2024, 3, 15), YearMonthDay(2026, 2, 10), untilToday = false),
        )
        // the last range ends today, without an end date
        assertEquals(
            listOf(YearMonthDay(2025, 6, 1) to YearMonthDay(2025, 12, 31), YearMonthDay(2026, 1, 1) to null),
            calendarYearRanges(YearMonthDay(2025, 6, 1), YearMonthDay(2026, 3, 1), untilToday = true),
        )
        assertEquals(listOf(YearMonthDay(2025, 6, 1) to YearMonthDay(2025, 6, 1)), calendarYearRanges(YearMonthDay(2025, 6, 1), YearMonthDay(2025, 6, 1), false))
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
    suspend fun dailyDownloadsAreRequestedByYearAfterA503() {
        val serie = createClient().downloads(1, DownloadRequestDimension.Day, DownloadCountType.Downloads, null).data.serie

        val thisYear = YearMonthDay.now().year
        // the single request, the first month of the monthly downloads, then a request of each year
        val daily = requests.filter { it.url.segments.last() == "day" }.map { it.url.parameters["startDate"] to it.url.parameters["endDate"] }
        val years = (2024..thisYear).map { year ->
            (if (year == 2024) "2024-03-01" else "$year-01-01") to (if (year == thisYear) null else "$year-12-31")
        }
        assertEquals(listOf(null to null) + years.sortedBy { it.first }, listOf(daily.first()) + daily.drop(1).sortedBy { it.first })
        assertEquals(years.map { it.first }, serie.map { it.name })
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
