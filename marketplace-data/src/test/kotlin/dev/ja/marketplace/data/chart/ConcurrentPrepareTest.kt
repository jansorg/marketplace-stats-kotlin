/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.chart

import dev.ja.marketplace.client.PluginReleaseId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.client.model.DownloadRequestDimension
import dev.ja.marketplace.exchangeRate.EcbExchangeRateData
import dev.ja.marketplace.exchangeRate.ExchangeRates
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Concurrent requests of the charts load the downloads of the same metric, e.g. by month for the calendar months and
 * by day for the last 7 days of a chart. The loads finish in any order, the downloads by day must not be replaced.
 */
class ConcurrentPrepareTest {
    private val today = YearMonthDay(2025, 9, 30)
    private val loads = mutableListOf<String>()

    /** The downloads of September 2025: 3 at September 1 and 4 at September 2, by month all at September 30. */
    private val source = object : ChartDownloadsSource {
        override suspend fun releases() = listOf(ChartParameterValue("1001", "1.0"))
        override suspend fun products() = listOf(ChartParameterValue("IIU", "IntelliJ IDEA"))
        override suspend fun majorVersions() = emptyList<ChartParameterValue>()
        override suspend fun majorVersionDownloads(branch: Int, start: YearMonthDay, byMonth: Boolean) = emptyList<DailyDownload>()

        override suspend fun downloads(release: PluginReleaseId?, product: String?, start: YearMonthDay, byMonth: Boolean): List<DailyDownload> {
            loads += if (byMonth) "month" else "day"
            // the downloads by month take longer, they finish last
            delay(if (byMonth) 300 else 10)
            return when {
                byMonth -> listOf(DailyDownload(YearMonthDay(2025, 9, 30), 7))
                else -> listOf(DailyDownload(YearMonthDay(2025, 9, 1), 3), DailyDownload(YearMonthDay(2025, 9, 2), 4))
            }
        }

        override suspend fun uniqueDownloads(release: PluginReleaseId?, product: String?, start: YearMonthDay, period: DownloadRequestDimension) =
            emptyList<DailyDownload>()
    }

    private val values = ChartMetricValues.freePlugin(listOf(DailyDownload(YearMonthDay(2025, 1, 10), 1)), ExchangeRates("USD", EcbExchangeRateData()), today, source)
    private val metric = ChartMetric.byId("release-downloads.1001")!!
    private fun at(window: ChartWindow, day: YearMonthDay) = values.series(metric, window)!!.values[values.days.indexOf(day)]

    @Test
    suspend fun downloadsByDayArentReplacedByDownloadsByMonth() {
        coroutineScope {
            launch { values.prepare(listOf(metric), byMonth = true) }
            launch { values.prepare(listOf(metric), byMonth = false) }
        }
        assertEquals(listOf("month", "day"), loads)
        // the downloads of each day, e.g. of the day September 2, not the month at its last day
        assertEquals(BigDecimal(4), at(ChartWindow.Day, YearMonthDay(2025, 9, 2)))
        assertEquals(BigDecimal(7), at(ChartWindow.CalendarMonth, YearMonthDay(2025, 9, 30)))
        assertEquals(BigDecimal(0), at(ChartWindow.Day, YearMonthDay(2025, 9, 30)))
    }

    @Test
    suspend fun downloadsOfAnotherReleaseArentLoaded() {
        val other = ChartMetric.byId("release-downloads.9999")!!
        values.prepare(listOf(other, ChartMetric.byId("release-downloads.1001.XYZ")!!))
        // the release and the product aren't those of the plugin, the lines have no values
        assertEquals(emptyList<String>(), loads)
        assertNull(values.series(other, ChartWindow.Day))
    }
}
