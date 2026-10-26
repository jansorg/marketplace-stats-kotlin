/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.persistentCache.PersistentHistorySettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * How long the data of the Marketplace and of the JetBrains services is cached, in memory and on disk.
 *
 * The data is grouped by how often it changes, see [Category]. Without the automatic refresh, cached data is fetched
 * again by the request, which needs it after its [maxAge].
 *
 * With the automatic refresh, the data of the plugins, which were requested, is fetched again in the background every
 * [autoRefreshInterval], before it's outdated, see [PluginAutoRefresh]. The recent data has the age of the interval.
 * Requests use the cached data for two more intervals, see [expiry], so that they don't wait for the Marketplace
 * while a refresh is running.
 *
 * @param autoRefreshInterval The interval of the automatic refresh, `null` if it's disabled.
 * @param autoRefreshIdleTime The automatic refresh of a plugin stops after this time without a request for the plugin.
 */
class CachingStrategy(
    val autoRefreshInterval: Duration? = null,
    val autoRefreshIdleTime: Duration = 1.days,
) {
    init {
        require(autoRefreshInterval == null || autoRefreshInterval.isPositive()) { "The interval of the automatic refresh must be positive" }
    }

    /**
     * @param maxAge The time after which cached data of the category is fetched again.
     */
    enum class Category(val maxAge: Duration) {
        /** Data, which changes often, e.g. the recent sales and trials, offers and reviews. */
        Recent(30.minutes),

        /** Data, which changes about once per day or less often, e.g. downloads, ratings, releases and the products of JetBrains. */
        Daily(1.days),

        /** Data, which rarely changes, e.g. prices and sales older than [UNSTABLE_HISTORY_DAYS] days. */
        Static(7.days),
    }

    /**
     * @return The age, after which cached data is outdated. The recent data is outdated after the interval of the
     *  automatic refresh, if it's enabled.
     */
    fun maxAge(category: Category): Duration {
        return when {
            category == Category.Recent && autoRefreshInterval != null -> autoRefreshInterval
            else -> category.maxAge
        }
    }

    /**
     * @return How long requests use cached data, before they fetch it again. With the automatic refresh, it's two
     *  intervals longer than [maxAge]: the data of refreshed plugins is replaced before, the data of other plugins is
     *  fetched again after this time.
     */
    fun expiry(category: Category): Duration {
        return autoRefreshInterval?.let { maxAge(category) + it * 2 } ?: maxAge(category)
    }

    /**
     * @return If the automatic refresh fetches cached data of the [age] again, because it'd be outdated before the next
     *  refresh. Always `false` without the automatic refresh.
     */
    fun isRefreshDue(category: Category, age: Duration): Boolean {
        val interval = autoRefreshInterval ?: return false
        return age + interval >= maxAge(category)
    }

    /**
     * The settings of the persistent caches of sales and trials: requests fetch recent data again after the [expiry] of
     * [Category.Recent], the complete history is fetched after the age of [Category.Static].
     * Stored data, which is older than a day, is updated before it's used, e.g. after a long downtime.
     */
    val historySettings: PersistentHistorySettings
        get() = PersistentHistorySettings(
            maxAge = expiry(Category.Recent),
            overlapDays = UNSTABLE_HISTORY_DAYS,
            fullFetchInterval = maxAge(Category.Static),
            backgroundUpdateMaxAge = maxAge(Category.Daily),
        )

    /**
     * @return The category of sales or trials of [range]: data of the recent days may still change.
     */
    fun historyCategory(range: YearMonthDayRange, today: YearMonthDay = YearMonthDay.now()): Category {
        return when {
            // the whole range must be older, the recent days of a range starting earlier may still change
            range.end daysUntil today > UNSTABLE_HISTORY_DAYS -> Category.Static
            else -> Category.Recent
        }
    }

    companion object {
        /** Sales and trials of this many recent days may still change, e.g. because of refunds. */
        const val UNSTABLE_HISTORY_DAYS: Int = 45
    }
}
