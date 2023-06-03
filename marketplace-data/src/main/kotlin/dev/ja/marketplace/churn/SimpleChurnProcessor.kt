/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange

/**
 * Churn rate is calculated as "number of users lost in the date range / users at beginning of the date range".
 */
class SimpleChurnProcessor<T>(
    private val previouslyActiveMarkerDate: YearMonthDay,
    private val currentlyActiveMarkerDate: YearMonthDay,
    private val graceTimeDays: Int,
) : ChurnProcessor<Int, T> {
    private val previousPeriodItems = mutableSetOf<Int>()
    private val activeItems = mutableSetOf<Int>()
    private val activeItemsUnaccepted = mutableSetOf<Int>()

    override fun init() {}

    override fun processValue(id: Int, value: T, validity: YearMonthDayRange, isAcceptedValue: Boolean) {
        if (isAcceptedValue && previouslyActiveMarkerDate in validity) {
            previousPeriodItems += id
        }

        // valid before end, valid until end or later
        if (currentlyActiveMarkerDate in validity.expandEnd(0, 0, graceTimeDays)) {
            if (isAcceptedValue) {
                activeItems += id
            } else {
                activeItemsUnaccepted += id
            }
        }
    }

    override fun getResult(): ChurnResult<T> {
        val activeAtStart = previousPeriodItems.size
        val churned = previousPeriodItems.count { it !in activeItems && it !in activeItemsUnaccepted }
        val churnRate = when (activeAtStart) {
            0 -> 0.0
            else -> churned.toDouble() / activeAtStart.toDouble()
        }

        return ChurnResult(churnRate, activeAtStart, activeItems.size, churned)
    }
}