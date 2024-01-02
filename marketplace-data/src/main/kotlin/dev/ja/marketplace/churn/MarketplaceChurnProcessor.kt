/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.LicensePeriod
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange

/**
 * Churn rate is calculated as "number of users lost in the date range / users at beginning of the date range".
 *
 * Churn processor, which uses the license type (new / renewal) to decide if a user churned or not.
 * Users with renewals even after the end of the range (e.g. after end of month) are considered as active and not as churned.
 * We're not using any kind of grace period because it's implicitly used if the license type is "renewal".
 */
class MarketplaceChurnProcessor<ID, T>(
    private val previouslyActiveMarkerDate: YearMonthDay,
    private val currentlyActiveMarkerDate: YearMonthDay,
    private val hashSetFactory: () -> MutableCollection<ID>,
) : ChurnProcessor<ID, T> {
    override fun init() {}

    private val previousPeriodItems: MutableCollection<ID> = hashSetFactory()
    private val activeItems: MutableCollection<ID> = hashSetFactory()
    private val activeItemsUnaccepted: MutableCollection<ID> = hashSetFactory()

    override fun processValue(
        id: ID,
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

    override fun getResult(period: LicensePeriod): ChurnResult<T> {
        val activeAtStart = previousPeriodItems.size

        // e.g. users which were licensed end of last month, but no longer are licensed end of this month.
        // We're not counting users, which switched the license type, e.g. from "monthly" to "annual"
        val churned = churnedIds()
        val churnRate = when (activeAtStart) {
            0 -> 0.0
            else -> churned.size.toDouble() / activeAtStart.toDouble()
        }

        return ChurnResult(
            churnRate,
            activeAtStart,
            activeItems.size,
            churned.size,
            previouslyActiveMarkerDate,
            currentlyActiveMarkerDate,
            period
        )
    }

    fun churnedIds(): Set<ID> {
        val churned = hashSetFactory()
        churned.addAll(previousPeriodItems)
        churned.removeAll(activeItems)
        churned.removeAll(activeItemsUnaccepted)
        return churned.toSet()
    }
}