/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.CustomerId
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.PluginInfo
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.data.IncompleteValue
import dev.ja.marketplace.data.PercentageValue

data class TrialTrackerInfo(
    val totalTrials: Int,
    val convertedTrials: Map<PluginTrial, PluginSale>,
    /**
     * Trials, which are not converted yet, but can still convert because their conversion period hasn't ended yet.
     */
    val pendingTrials: Int = 0,
) {
    val convertedTrialsCount: Int = convertedTrials.size

    val convertedTrialsPercentage: PercentageValue
        get() {
            return PercentageValue.of(convertedTrialsCount, totalTrials)
        }

    val isIncomplete: Boolean
        get() {
            return pendingTrials > 0
        }

    /**
     * The conversion rate for display, marked as incomplete if trials can still convert.
     */
    val displayedConversionRate: Any
        get() {
            return when {
                isIncomplete -> IncompleteValue(convertedTrialsPercentage)
                else -> convertedTrialsPercentage
            }
        }

    fun getTooltipConverted(maxConversionDays: Int? = null): String {
        val converted = when {
            maxConversionDays == null -> "$convertedTrialsCount trials of $totalTrials converted ($convertedTrialsPercentage)"
            else -> "$convertedTrialsCount trials of $totalTrials converted within $maxConversionDays days ($convertedTrialsPercentage)"
        }
        return when {
            pendingTrials > 0 -> "$converted, $pendingTrials trials can still convert"
            else -> converted
        }
    }
}

interface TrialTracker {
    fun init(allTrials: Iterable<PluginTrial>)

    fun processSale(saleInfo: PluginSale)

    fun getResult(
        trialStartDateRange: YearMonthDayRange,
        isPendingTrial: (PluginTrial) -> Boolean = { false },
        isValidConversion: (PluginTrial, PluginSale) -> Boolean = { _, _ -> true },
    ): TrialTrackerInfo
}

/** The length of the trials of the plugin in days. */
val PluginInfo.maxTrialDays: Int
    get() = purchaseInfo?.trialPeriod ?: Marketplace.MAX_TRIAL_DAYS_DEFAULT

/**
 * @return The tooltip of a conversion rate: the trials converted at any time and within [trialConversionDays].
 */
fun conversionTooltip(anyDuration: TrialTrackerInfo, trialDuration: TrialTrackerInfo, maxTrialDays: Int): String {
    return anyDuration.getTooltipConverted() + "\n" + trialDuration.getTooltipConverted(trialConversionDays(maxTrialDays))
}

/**
 * @return The number of days after the start of a trial, in which a purchase counts as a converted trial.
 */
fun trialConversionDays(maxTrialDays: Int): Int {
    return maxTrialDays + Marketplace.TRIAL_CONVERSION_EXTRA_DAYS
}

/**
 * Trials converted by a purchase within the trial period and [Marketplace.TRIAL_CONVERSION_EXTRA_DAYS] days after it.
 * Trials, which can still convert today, are counted as pending.
 */
fun TrialTracker.getResultWithinConversionPeriod(
    trialStartDateRange: YearMonthDayRange,
    maxTrialDays: Int,
    today: YearMonthDay = YearMonthDay.now(),
): TrialTrackerInfo {
    val conversionDays = trialConversionDays(maxTrialDays)
    return getResult(
        trialStartDateRange,
        isPendingTrial = { trial -> trial.date.daysUntil(today) <= conversionDays },
        isValidConversion = { trial, sale -> trial.date.daysUntil(sale.date) <= conversionDays },
    )
}

fun TrialTracker.getResultBySaleDate(trialStartDateRange: YearMonthDayRange, saleDateRange: YearMonthDayRange): TrialTrackerInfo {
    return getResult(trialStartDateRange) { _, sale ->
        sale.date in saleDateRange
    }
}

class SimpleTrialTracker : TrialTracker {
    private data class TrialRecord(val trial: PluginTrial, var nextSale: PluginSale? = null)

    private val trialRecords = mutableListOf<TrialRecord>()
    private val trialsByCustomerId = mutableMapOf<CustomerId, MutableList<TrialRecord>>()

    override fun init(allTrials: Iterable<PluginTrial>) {
        for (trial in allTrials) {
            // the same record must be shared in the maps
            val trialRecord = TrialRecord(trial)
            trialRecords += trialRecord
            trialsByCustomerId.getOrPut(trial.customer.code, ::mutableListOf) += trialRecord
        }
    }

    override fun processSale(saleInfo: PluginSale) {
        if (!isTrialConversion(saleInfo)) {
            return
        }

        val sortedTrials = trialsByCustomerId[saleInfo.customer.code]
        if (!sortedTrials.isNullOrEmpty()) {
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
        isPendingTrial: (PluginTrial) -> Boolean,
        isValidConversion: (PluginTrial, PluginSale) -> Boolean,
    ): TrialTrackerInfo {
        var validTrialsCount = 0
        var pendingTrialsCount = 0
        val convertedTrials = mutableMapOf<PluginTrial, PluginSale>()

        // iterating to avoid expensive and unnecessary filtering and flattening of the multimap
        for ((trial, sale) in trialRecords) {
            if (trial.date in trialStartDateRange) {
                validTrialsCount++
                if (sale != null && isValidConversion(trial, sale)) {
                    convertedTrials[trial] = sale
                } else if (isPendingTrial(trial)) {
                    pendingTrialsCount++
                }
            }
        }

        return TrialTrackerInfo(validTrialsCount, convertedTrials, pendingTrialsCount)
    }

    /**
     * Only the purchase of a new, paid license converts a trial.
     * Renewals and free licenses, e.g. with a 100% discount, are not counted.
     */
    private fun isTrialConversion(sale: PluginSale): Boolean {
        return sale.lineItems.any { item ->
            item.type == PluginSaleItemType.New && !item.isFreeLicense && !item.amountUSD.isZero
        }
    }
}
