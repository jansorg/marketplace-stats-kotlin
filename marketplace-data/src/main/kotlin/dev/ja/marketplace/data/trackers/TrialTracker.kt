/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import com.google.common.collect.Multimaps
import dev.ja.marketplace.client.CustomerId
import dev.ja.marketplace.client.PluginSale
import dev.ja.marketplace.client.PluginTrial
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.data.PercentageValue

data class TrialTrackerInfo(
    val totalTrials: Int,
    val convertedTrials: Map<PluginTrial, PluginSale>,
) {
    val convertedTrialsCount: Int = convertedTrials.size

    val convertedTrialsPercentage: PercentageValue
        get() {
            return PercentageValue.of(convertedTrialsCount, totalTrials)
        }

    fun getTooltipConverted(maxTrialDays: Int? = null): String {
        return when {
            maxTrialDays == null -> "$convertedTrialsCount trials of $totalTrials converted ($convertedTrialsPercentage)"
            else -> "$convertedTrialsCount trials of $totalTrials converted within $maxTrialDays days ($convertedTrialsPercentage)"
        }
    }
}

/**
 * Tracks conversion of trials into subscriptions.
 */
interface TrialTracker {
    fun init(allTrials: Iterable<PluginTrial>)

    fun processSale(saleInfo: PluginSale)

    fun getResult(
        trialStartDateRange: YearMonthDayRange,
        isValidConversion: (PluginTrial, PluginSale) -> Boolean = { _, _ -> true }
    ): TrialTrackerInfo
}

fun TrialTracker.getResultByTrialDuration(trialStartDateRange: YearMonthDayRange, maxTrialDays: Int): TrialTrackerInfo {
    return getResult(trialStartDateRange) { trial, sale ->
        trial.date.daysUntil(sale.date) <= maxTrialDays
    }
}

fun TrialTracker.getResultBySaleDate(trialStartDateRange: YearMonthDayRange, saleDateRange: YearMonthDayRange): TrialTrackerInfo {
    return getResult(trialStartDateRange) { _, sale ->
        sale.date in saleDateRange
    }
}

class SimpleTrialTracker : TrialTracker {
    private data class TrialRecord(val trial: PluginTrial, var nextSale: PluginSale? = null)

    private val trialRecords = mutableListOf<TrialRecord>()
    private val trialsByCustomerId = Multimaps.newListMultimap<CustomerId, TrialRecord>(mutableMapOf(), ::ArrayList)

    override fun init(allTrials: Iterable<PluginTrial>) {
        for (trial in allTrials) {
            // the same record must be shared in the maps
            val trialRecord = TrialRecord(trial)
            trialRecords += trialRecord
            trialsByCustomerId.put(trial.customer.code, trialRecord)
        }
    }

    override fun processSale(saleInfo: PluginSale) {
        val sortedTrials = trialsByCustomerId[saleInfo.customer.code]//.sortedBy { it.trial.date }
        if (sortedTrials.isNotEmpty()) {
            val newestTrialForSale = sortedTrials.lastOrNull { it.trial.date <= saleInfo.date }
            if (newestTrialForSale != null) {
                val existingSaleDate = newestTrialForSale.nextSale?.date
                if (existingSaleDate == null || existingSaleDate > saleInfo.date) {
                    newestTrialForSale.nextSale = saleInfo
                }
            }
        }
    }

    override fun getResult(
        trialStartDateRange: YearMonthDayRange,
        isValidConversion: (PluginTrial, PluginSale) -> Boolean,
    ): TrialTrackerInfo {
        var validTrialsCount = 0
        val convertedTrials = mutableMapOf<PluginTrial, PluginSale>()

        // iterating to avoid expensive and unnecessary filtering and flattening of the multimap
        for ((trial, sale) in trialRecords) {
            if (trial.date in trialStartDateRange) {
                validTrialsCount++
                if (sale != null && isValidConversion(trial, sale)) {
                    convertedTrials[trial] = sale
                }
            }
        }

        return TrialTrackerInfo(validTrialsCount, convertedTrials)
    }
}