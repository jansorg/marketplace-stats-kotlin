/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.LicensePeriod
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange

/**
 * Churn rate is calculated as "number of users lost in the date range / users at beginning of the date range".
 *
 * This implementation is problematic, because
 * - the grace period is not necessarily the same as the one used by JetBrains Marketplace
 */
class SimpleChurnProcessor<T>(
    private val previouslyActiveMarkerDate: YearMonthDay,
    private val currentlyActiveMarkerDate: YearMonthDay,
    graceTimeDays: Int,
) : ChurnProcessor<Int, T> {
    private val previouslyActiveMarkerDateWithGraceTime = previouslyActiveMarkerDate.add(0, 0, graceTimeDays)
    private val currentlyActiveMarkerDateWithGraceTime = currentlyActiveMarkerDate.add(0, 0, graceTimeDays)

    private val previousPeriodItems = mutableSetOf<Int>()
    private val activeItems = mutableSetOf<Int>()
    private val activeItemsUnaccepted = mutableSetOf<Int>()

    override fun init() {}

    override fun processValue(
        id: Int,
        value: T,
        validity: YearMonthDayRange,
        isAcceptedValue: Boolean,
        isExplicitRenewal: Boolean
    ) {
        if (isAcceptedValue && previouslyActiveMarkerDate in validity) {
            previousPeriodItems += id
        }

        // valid before end, valid until end or later
        if (isValid(validity, currentlyActiveMarkerDate, currentlyActiveMarkerDateWithGraceTime)) {
            if (isAcceptedValue) {
                activeItems += id
            } else {
                activeItemsUnaccepted += id
            }
        }
    }

    override fun getResult(period: LicensePeriod): ChurnResult<T> {
        val activeAtStart = previousPeriodItems.size
        val churned = previousPeriodItems.count { it !in activeItems && it !in activeItemsUnaccepted }
        val churnRate = when (activeAtStart) {
            0 -> 0.0
            else -> churned.toDouble() / activeAtStart.toDouble()
        }

        return ChurnResult(
            churnRate,
            activeAtStart,
            activeItems.size,
            churned,
            previouslyActiveMarkerDate,
            currentlyActiveMarkerDate,
            period
        )
    }

    private fun isValid(
        validity: YearMonthDayRange,
        markerDate: YearMonthDay,
        markerDateWithGraceTime: YearMonthDay
    ): Boolean {
        return markerDate in validity || markerDateWithGraceTime in validity
    }
}