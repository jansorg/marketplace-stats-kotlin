/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.churn

import dev.ja.marketplace.client.YearMonthDay
import it.unimi.dsi.fastutil.ints.IntArraySet

abstract class MarketplaceIntChurnProcessor<T>(
    previouslyActiveMarkerDate: YearMonthDay,
    currentlyActiveMarkerDate: YearMonthDay
) : MarketplaceChurnProcessor<T>(previouslyActiveMarkerDate, currentlyActiveMarkerDate) {
    private val previousPeriodItems = IntArraySet(250)
    private val activeItems = IntArraySet(250)
    private val activeUnacceptedItems = IntArraySet(250)

    protected abstract fun getId(value: T): Int

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
        val churned = churnedIds()
        return churned.size
    }

    fun churnedIds(): Set<Int> {
        val churned = IntArraySet(previousPeriodItems)
        churned.removeAll(activeItems)
        churned.removeAll(activeUnacceptedItems)
        return churned
    }
}