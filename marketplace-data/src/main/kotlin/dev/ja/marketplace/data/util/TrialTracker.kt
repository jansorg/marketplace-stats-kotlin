/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.util

import dev.ja.marketplace.client.CustomerId
import dev.ja.marketplace.client.PluginSale
import dev.ja.marketplace.client.PluginTrial
import dev.ja.marketplace.data.PercentageValue

data class TrialTrackerInfo(
    val totalTrials: Int,
    val convertedTrialsCount: Int,
    val convertedTrials: Map<PluginTrial, PluginSale>,
) {
    val convertedTrialsPercentage: PercentageValue
        get() {
            return PercentageValue.of(convertedTrialsCount, totalTrials)
        }
}

/**
 * Tracks conversion of trials into subscriptions.
 */
interface TrialTracker {
    fun registerTrial(trial: PluginTrial)

    fun processSale(saleInfo: PluginSale)

    fun getResult(): TrialTrackerInfo
}

class SimpleTrialTracker() : TrialTracker {
    private val trials = mutableMapOf<CustomerId, PluginTrial>()
    private val convertedTrials = mutableMapOf<CustomerId, MutableList<PluginSale>>()

    override fun registerTrial(trial: PluginTrial) {
        // only record the latest trials
        if (trial.customer.code !in this.trials) {
            this.trials[trial.customer.code] = trial
        }
    }

    override fun processSale(saleInfo: PluginSale) {
        val customerId = saleInfo.customer.code
        val trial = trials[customerId]
        if (trial != null && trial.date <= saleInfo.date) {
            convertedTrials.getOrPut(customerId, ::ArrayList) += saleInfo
        }
    }

    override fun getResult(): TrialTrackerInfo {
        val convertedTrialsList = convertedTrials.mapKeys { trials[it.key]!! }
        return TrialTrackerInfo(
            trials.size,
            convertedTrials.size,
            convertedTrialsList.mapValues { it.value.minBy { it.date } }
        )
    }
}