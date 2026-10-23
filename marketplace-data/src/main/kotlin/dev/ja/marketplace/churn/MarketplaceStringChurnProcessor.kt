/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.YearMonthDay
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet

abstract class MarketplaceStringChurnProcessor<T>(
    previouslyActiveMarkerDate: YearMonthDay,
    currentlyActiveMarkerDate: YearMonthDay
) : MarketplaceChurnProcessor<T>(previouslyActiveMarkerDate, currentlyActiveMarkerDate) {
    private val previousPeriodItems = ObjectOpenHashSet<String>()
    private val activeItems = ObjectOpenHashSet<String>()
    private val activeUnacceptedItems = ObjectOpenHashSet<String>()

    protected abstract fun getId(value: T): String

    override fun previousPeriodItemCount(): Int {
        return previousPeriodItems.size
    }

    override fun activeItemsCount(): Int {
        return activeItems.size
    }

    override fun addPreviousPeriodItem(value: T) {
        previousPeriodItems += getId(value)
    }

    override fun addActiveItem(value: T) {
        activeItems += getId(value)
    }

    override fun addActiveUnacceptedItem(value: T) {
        activeUnacceptedItems += getId(value)
    }

    override fun churnedItemsCount(): Int {
        return churnedIds().size
    }

    fun churnedIds(): Set<String> {
        val churned = ObjectOpenHashSet<String>(previousPeriodItems)
        churned.removeAll(activeItems)
        churned.removeAll(activeUnacceptedItems)
        return churned
    }
}