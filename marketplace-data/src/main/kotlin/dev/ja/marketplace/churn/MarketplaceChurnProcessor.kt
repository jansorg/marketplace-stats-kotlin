/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import it.unimi.dsi.fastutil.ints.IntOpenHashSet

/**
 * Churn rate is calculated as "number of users lost in the date range / users at beginning of the date range".
 *
 * Churn processor, which uses the license type (new / renewal) to decide if a user churned or not.
 * Users with renewals even after the end of the range (e.g. after end of month) are considered as active and not as churned.
 * We're not using any kind of grace period, because it's implicitly used if the license type is "renewal".
 */
class MarketplaceChurnProcessor<T>(
    private val previouslyActiveMarkerDate: YearMonthDay,
    private val currentlyActiveMarkerDate: YearMonthDay
) : ChurnProcessor<Int, T> {
    override fun init() {}

    private val previousPeriodItems = IntOpenHashSet()
    private val activeItems = IntOpenHashSet()
    private val activeItemsUnaccepted = IntOpenHashSet()

    override fun processValue(
        id: Int,
        value: T,
        validity: YearMonthDayRange,
        isAcceptedValue: Boolean,
        isExplicitRenewal: Boolean
    ) {
        if (isAcceptedValue && previouslyActiveMarkerDate in validity) {
            previousPeriodItems.add(id)
        }

        if (currentlyActiveMarkerDate in validity || isExplicitRenewal && validity.end > currentlyActiveMarkerDate) {
            when {
                isAcceptedValue -> activeItems.add(id)
                else -> activeItemsUnaccepted.add(id)
            }
        }
    }

    override fun getResult(): ChurnResult<T> {
        val activeAtStart = previousPeriodItems.size

        // e.g. users which were licensed end of last month, but no longer are licensed end of this month.
        // We're not counting users, which switched the license type, e.g. from "monthly" to "annual"
        val churned = IntOpenHashSet(previousPeriodItems)
        churned.removeAll(activeItems)
        churned.removeAll(activeItemsUnaccepted)

        val churnRate = when (activeAtStart) {
            0 -> 0.0
            else -> churned.size.toDouble() / activeAtStart.toDouble()
        }

        return ChurnResult(churnRate, activeAtStart, activeItems.size, churned.size)
    }
}