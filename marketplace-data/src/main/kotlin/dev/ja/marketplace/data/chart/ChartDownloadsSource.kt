/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.chart

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.PluginChannelNames
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.PluginReleaseId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.DailyDownload
import dev.ja.marketplace.client.model.DownloadCountType
import dev.ja.marketplace.client.model.DownloadFilter
import dev.ja.marketplace.client.model.DownloadFilterType
import dev.ja.marketplace.client.model.DownloadRequestDimension
import dev.ja.marketplace.data.releases.PluginReleases
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Loads the downloads of the releases and the products of a plugin for the metrics of
 * [ChartMetricFamily.ReleaseDownloads] and [ChartMetricFamily.ProductDownloads], and the values of their parameters.
 * The data is loaded when a metric is requested, see [ChartMetricValues.prepare].
 */
interface ChartDownloadsSource {
    /** @return The releases of the plugin, newest first: their IDs and versions. */
    suspend fun releases(): List<ChartParameterValue>

    /** @return The products, in which the plugin was downloaded, the most downloads first: their codes and names. */
    suspend fun products(): List<ChartParameterValue>

    /**
     * @param start The first day of the charts, the downloads of a release start at its release date at the earliest.
     * @param byMonth If the downloads of each month are loaded instead of each day, which is faster: the downloads of
     *  a month are counted at its last day, of the current month at today. Sums of calendar windows are exact at the
     *  ends of the months, e.g. of the levels Month and Year, other values aren't.
     * @return The downloads of each day, of a release and of a product, of all releases or products for `null`.
     */
    suspend fun downloads(release: PluginReleaseId?, product: String?, start: YearMonthDay, byMonth: Boolean = false): List<DailyDownload>

    companion object {
        fun of(client: MarketplaceClient, pluginId: PluginId): ChartDownloadsSource = object : ChartDownloadsSource {
            override suspend fun releases(): List<ChartParameterValue> {
                return PluginReleases.byChannel(client, pluginId)
                    .flatMap { (channel, releases) ->
                        releases.map { release ->
                            val label = when (channel) {
                                PluginChannelNames.Stable -> release.version
                                else -> "${release.version} (${PluginReleases.channelTitle(channel)})"
                            }
                            release.createdTimestamp to ChartParameterValue(release.id.toString(), label)
                        }
                    }
                    .sortedByDescending { it.first }
                    .map { it.second }
            }

            override suspend fun products(): List<ChartParameterValue> {
                return client.downloads(pluginId, DownloadRequestDimension.ProductCode, DownloadCountType.Downloads).data.serie
                    // the downloads without a known product can't be filtered
                    .filter { it.value > 0 && ChartMetricFamily.isProductCode(it.name) }
                    .sortedByDescending { it.value }
                    .map { ChartParameterValue(it.name, it.productLabel) }
            }

            override suspend fun downloads(release: PluginReleaseId?, product: String?, start: YearMonthDay, byMonth: Boolean): List<DailyDownload> = coroutineScope {
                val filters = listOfNotNull(
                    release?.let(DownloadFilter::update),
                    product?.let { DownloadFilter(DownloadFilterType.ProductCode, it) },
                ).toTypedArray()
                // a release has no downloads before its release date
                val releaseDate = release?.let { id -> releasesById().get(id)?.let { YearMonthDay.of(it.createdTimestamp) } }
                val first = maxOf(start, releaseDate ?: start)
                val today = YearMonthDay.now()
                if (byMonth) {
                    return@coroutineScope yearChunks(first, today)
                        .map { (chunkStart, chunkEnd) ->
                            async { client.downloads(pluginId, DownloadRequestDimension.Month, DownloadCountType.Downloads, chunkStart, *filters, endDate = chunkEnd).data.serie }
                        }
                        .awaitAll()
                        .flatten()
                        .map { item ->
                            val month = YearMonthDay.parse(item.name)
                            DailyDownload(minOf(YearMonthDay.lastOfMonth(month.year, month.month), today), item.value)
                        }
                        .distinctBy { it.day }
                        .sortedBy { it.day }
                }
                halfMonthChunks(first, today)
                    .map { (chunkStart, chunkEnd) ->
                        async { client.downloads(pluginId, DownloadRequestDimension.Day, DownloadCountType.Downloads, chunkStart, *filters, endDate = chunkEnd).data.serie }
                    }
                    .awaitAll()
                    .flatten()
                    .map { DailyDownload(YearMonthDay.parse(it.name), it.value) }
                    .distinctBy { it.day }
                    .sortedBy { it.day }
            }

            private suspend fun releasesById() = PluginReleases.byChannel(client, pluginId).values.flatten().associateBy { it.id }
        }
    }
}

/**
 * @return The halves of the months from the half of [start] until [today], the requests of the downloads by day: the
 *  statistics service answers requests, which take longer than about 30 seconds, with 503, e.g. the downloads of a
 *  release in a product of a quarter of a year. The halves are the days 1 to 15 and 16 to the end of a month, the
 *  same for all requests, so that the cached downloads of overlapping time ranges are used again. The current half
 *  ends with `null`, until today, the past halves have an end, their cached downloads are complete.
 */
internal fun halfMonthChunks(start: YearMonthDay, today: YearMonthDay): List<Pair<YearMonthDay, YearMonthDay?>> {
    val chunks = mutableListOf<Pair<YearMonthDay, YearMonthDay?>>()
    var chunkStart = if (start.day >= 16) YearMonthDay(start.year, start.month, 16) else YearMonthDay.firstOfMonth(start.year, start.month)
    while (chunkStart <= today) {
        val next = if (chunkStart.day == 1) YearMonthDay(chunkStart.year, chunkStart.month, 16) else chunkStart.atFirstOfMonth().add(0, 1, 0)
        val chunkEnd = next.add(0, 0, -1)
        chunks += chunkStart to chunkEnd.takeIf { it < today }
        chunkStart = next
    }
    return chunks
}

/**
 * @return The calendar years from the year of [start] until [today], the requests of the downloads by month. The
 *  years start at January 1, the same for all requests, so that their cached downloads are used again. The current
 *  year ends with `null`, until today.
 *
 *  The statistics service groups the downloads by calendar month and only counts whole months: a start within a
 *  month leaves out the month, an end within a month is ignored, the month is counted until its end. The requests
 *  by month must therefore start at the first day of a month and end at the last day of a month or today.
 */
internal fun yearChunks(start: YearMonthDay, today: YearMonthDay): List<Pair<YearMonthDay, YearMonthDay?>> {
    return (start.year..today.year).map { year -> YearMonthDay(year, 1, 1) to YearMonthDay(year, 12, 31).takeIf { year < today.year } }
}
