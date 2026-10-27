/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange

/**
 * Churn rate is calculated as "number of users lost in the date range / users at beginning of the date range".
 *
 * A value, e.g. a license, is active at the start of the range if it's valid at [previouslyActiveMarkerDate] and churned if
 * it's no longer valid at [currentlyActiveMarkerDate]. The validities must cover the grace period of renewals, see
 * [ChurnValidities]: a license renewed within the grace period is valid until the start of its renewal.
 *
 * This class is abstract to optimize performance. Subclasses store the values in sets without boxing Int IDs.
 */
abstract class MarketplaceChurnProcessor<T>(
    private val previouslyActiveMarkerDate: YearMonthDay,
    private val currentlyActiveMarkerDate: YearMonthDay,
) : ChurnProcessor<T> {
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
    ) {
        if (isAcceptedValue && previouslyActiveMarkerDate in validity) {
            addPreviousPeriodItem(value)
        }

        if (currentlyActiveMarkerDate in validity) {
            when {
                isAcceptedValue -> addActiveItem(value)
                else -> addActiveUnacceptedItem(value)
            }
        }
    }

    override fun getResult(period: LicensePeriod?): ChurnResult<T> {
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