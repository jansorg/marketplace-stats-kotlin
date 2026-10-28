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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class ChartDownloadsSourceTest {
    /** The requested dimensions and ranges, e.g. `Month 2025-01-01..2025-12-31`. */
    private val requests = mutableListOf<String>()

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

            else -> throw UnsupportedOperationException(method.name)
        }
    } as MarketplaceClient

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
}
