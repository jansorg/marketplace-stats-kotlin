/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.YearMonthDay
import it.unimi.dsi.fastutil.ints.IntOpenHashSet

abstract class MarketplaceIntChurnProcessor<T>(
    previouslyActiveMarkerDate: YearMonthDay,
    currentlyActiveMarkerDate: YearMonthDay
) : MarketplaceChurnProcessor<T>(previouslyActiveMarkerDate, currentlyActiveMarkerDate) {
    private val previousPeriodItems = IntOpenHashSet()
    private val activeItems = IntOpenHashSet()
    private val activeUnacceptedItems = IntOpenHashSet()

    protected abstract fun getId(value: T): Int

    override fun previousPeriodItemCount(): Int {
        return previousPeriodItems.size
    }

    override fun activeItemsCount(): Int {
        return activeItems.size
    }

    override fun addPreviousPeriodItem(value: T) {
        previousPeriodItems.add(getId(value))
    }

    override fun addActiveItem(value: T) {
        activeItems.add(getId(value))
    }

    override fun addActiveUnacceptedItem(value: T) {
        activeUnacceptedItems.add(getId(value))
    }

    override fun churnedItemsCount(): Int {
        val churned = churnedIds()
        return churned.size
    }

    fun churnedIds(): Set<Int> {
        val churned = IntOpenHashSet(previousPeriodItems)
        churned.removeAll(activeItems)
        churned.removeAll(activeUnacceptedItems)
        return churned
    }
}