/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.CachingStrategy.Category
import dev.ja.marketplace.client.model.DownloadResponseItem
import java.util.concurrent.ConcurrentHashMap

/**
 * The Marketplace publishes the downloads of a day late, during the next day. Until then, the downloads of yesterday
 * are 0, also in the monthly and total downloads. Responses, which were fetched before the downloads of yesterday were
 * published, are cached like recent data ([Category.Recent]) instead of a day ([Category.Daily]).
 *
 * The downloads of yesterday are published, if a response grouped by day contains them with a value. Responses of
 * the plugin, which were fetched at that time or later, are complete. A plugin without downloads yesterday is fetched
 * again like recent data until the end of the day.
 *
 * Responses, which end at least [COMPLETE_AFTER_DAYS] days before today, e.g. the parts of long time ranges, are
 * complete and cached like rarely changing data ([Category.Static]), in memory and on disk.
 *
 * @param today The current day, for tests.
 */
internal class DownloadsCompleteness(private val today: () -> YearMonthDay = YearMonthDay::now) {
    /** By plugin: the latest day with published downloads and when the response with them was fetched. */
    private val publishedDays = ConcurrentHashMap<PluginId, Pair<YearMonthDay, Long>>()

    /**
     * Records the downloads of a response grouped by day.
     *
     * @param days The downloads of each day, e.g. the series of a response or the sums of the series of its groups.
     * @param fetchedMillis When the response was fetched.
     */
    fun recordDays(plugin: PluginId, days: Map<YearMonthDay, Long>, fetchedMillis: Long) {
        val yesterday = today().add(0, 0, -1)
        if ((days[yesterday] ?: 0) <= 0) {
            return
        }
        publishedDays.merge(plugin, yesterday to fetchedMillis) { old, new ->
            when {
                new.first > old.first -> new
                new.first == old.first && new.second < old.second -> new
                else -> old
            }
        }
    }

    /**
     * @param endDate The last day of the downloads of the response, `null` if it ends today.
     * @return How long a response of the plugin, which was fetched at [fetchedMillis], is cached.
     */
    fun category(plugin: PluginId, fetchedMillis: Long, endDate: YearMonthDay? = null): Category {
        val yesterday = today().add(0, 0, -1)
        // the downloads of the past don't change anymore, only those of the recent days may be published late
        if (isComplete(endDate)) {
            return Category.Static
        }
        // a response, which ends before yesterday, doesn't change anymore
        if (endDate != null && endDate < yesterday) {
            return Category.Daily
        }
        val (day, publishedMillis) = publishedDays[plugin] ?: return Category.Recent
        return when {
            day >= yesterday && fetchedMillis >= publishedMillis -> Category.Daily
            else -> Category.Recent
        }
    }

    /**
     * @return If the downloads of a response, which ends at [endDate], don't change anymore: it ends at least
     *  [COMPLETE_AFTER_DAYS] days before today. Responses until today, `null`, may change.
     */
    fun isComplete(endDate: YearMonthDay?): Boolean {
        return endDate != null && endDate <= today().add(0, 0, -COMPLETE_AFTER_DAYS)
    }

    companion object {
        /** The days, after which the downloads of a day are complete, see [isComplete]. */
        const val COMPLETE_AFTER_DAYS = 7

        /** @return The downloads of each day of a series grouped by day, items with other names are ignored. */
        fun days(series: Iterable<DownloadResponseItem>): Map<YearMonthDay, Long> {
            val result = HashMap<YearMonthDay, Long>()
            for (item in series) {
                val day = try {
                    YearMonthDay.parse(item.name)
                } catch (_: Exception) {
                    continue
                }
                result.merge(day, item.value, Long::plus)
            }
            return result
        }
    }
}
