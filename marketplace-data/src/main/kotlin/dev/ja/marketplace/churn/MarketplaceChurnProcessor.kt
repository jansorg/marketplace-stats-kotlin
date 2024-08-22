/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange

/**
 * Churn rate is calculated as "number of users lost in the date range / users at beginning of the date range".
 *
 * Churn processor, which uses the license type (new / renewal) to decide if a user churned or not.
 * Users with renewals even after the end of the range (e.g. after end of month) are considered as active and not as churned.
 * We're not using any kind of grace period because it's implicitly used if the license type is "renewal".
 *
 * This class is abstract top optimize performance. Subclasses define non-generic getId() methods to avoid boxing Int IDs to objects.
 */
abstract class MarketplaceChurnProcessor<T>(
    private val previouslyActiveMarkerDate: YearMonthDay,
    private val currentlyActiveMarkerDate: YearMonthDay,
) : ChurnProcessor<T> {
    override fun init() {}

    protected abstract fun previousPeriodItemCount(): Int

    protected abstract fun activeItemsCount(): Int

    protected abstract fun addPreviousPeriodItem(value: T)

    protected abstract fun addActiveItem(value: T)

    protected abstract fun addActiveUnacceptedItem(value: T)

    protected abstract fun churnedItemsCount(): Int

    override fun processValue(
        value: T,
        validity: YearMonthDayRange,
        isAcceptedValue: Boolean,
        isExplicitRenewal: Boolean
    ) {
        if (isAcceptedValue && previouslyActiveMarkerDate in validity) {
            addPreviousPeriodItem(value)
        }

        if (currentlyActiveMarkerDate in validity || isExplicitRenewal && validity.end > currentlyActiveMarkerDate) {
            when {
                isAcceptedValue -> addActiveItem(value)
                else -> addActiveUnacceptedItem(value)
            }
        }
    }


    override fun getResult(period: LicensePeriod): ChurnResult<T> {
        val activeAtStart = previousPeriodItemCount()

        // For example, users which were licensed end of last month, but no longer are licensed end of this month.
        // We're not counting users, which switched the license type, e.g. from "monthly" to "annual"
        val churnedCount = churnedItemsCount()
        val churnRate = when (activeAtStart) {
            0 -> 0.0
            else -> churnedCount.toDouble() / activeAtStart.toDouble()
        }

        return ChurnResult(
            churnRate,
            activeAtStart,
            activeItemsCount(),
            churnedCount,
            previouslyActiveMarkerDate,
            currentlyActiveMarkerDate,
            period
        )
    }
}