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
 * The data is grouped by how often it changes, see [Category]. Cached data is fetched again by the request, which needs
 * it after its [maxAge].
 */
class CachingStrategy {
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

    fun maxAge(category: Category): Duration = category.maxAge

    /**
     * The settings of the persistent caches of sales and trials: recent data is fetched again after the age of
     * [Category.Recent], the complete history after the age of [Category.Static].
     * Stored data, which is older than a day, is updated before it's used, e.g. after a long downtime.
     */
    val historySettings: PersistentHistorySettings
        get() = PersistentHistorySettings(
            maxAge = maxAge(Category.Recent),
            overlapDays = UNSTABLE_HISTORY_DAYS,
            fullFetchInterval = maxAge(Category.Static),
            backgroundUpdateMaxAge = maxAge(Category.Daily),
        )

    /**
     * @return The category of sales or trials of [range]: data of the recent days may still change.
     */
    fun historyCategory(range: YearMonthDayRange, today: YearMonthDay = YearMonthDay.now()): Category {
        return when {
            range.start daysUntil today > UNSTABLE_HISTORY_DAYS -> Category.Static
            else -> Category.Recent
        }
    }

    companion object {
        /** Sales and trials of this many recent days may still change, e.g. because of refunds. */
        const val UNSTABLE_HISTORY_DAYS: Int = 45
    }
}
