/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.funnel

import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.SimpleTrialTracker
import dev.ja.marketplace.data.trackers.TrialTracker
import dev.ja.marketplace.data.trackers.getResultByTrialDuration
import kotlin.math.absoluteValue

class FunnelTable : SimpleDataTable("Trial Funnel", "funnel", "table-centered sortable"), MarketplaceDataSink {
    private var pluginId: PluginId? = null
    private var maxTrialDays: Int = Marketplace.MAX_TRIAL_DAYS_DEFAULT
    private val trialTracker: TrialTracker = SimpleTrialTracker()

    private val trialDateColumn = DataTableColumn("funnel-trial", "Trial Start Date", "date", preSorted = AriaSortOrder.Descending)
    private val licensedDateColumn = DataTableColumn("funnel-license", "Licensed Date", "date")
    private val testDurationColumn = DataTableColumn("funnel-test-duration", "Test Duration (days)", "num")
    private val customerColumn = DataTableColumn("funnel-customer", "Customer", "num")

    override val columns: List<DataTableColumn> = listOf(trialDateColumn, licensedDateColumn, testDurationColumn, customerColumn)

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.pluginId = data.pluginId
        this.maxTrialDays = data.getPluginInfo().purchaseInfo?.trialPeriod ?: Marketplace.MAX_TRIAL_DAYS_DEFAULT

        trialTracker.init(data.getTrials() ?: emptyList())
    }

    override suspend fun process(sale: PluginSale) {
        trialTracker.processSale(sale)
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        // empty
    }

    override suspend fun createSections(): List<DataTableSection> {
        val trialResult = trialTracker.getResultByTrialDuration(YearMonthDayRange.MAX, maxTrialDays)

        val rows = trialResult.convertedTrials.entries
            .sortedByDescending { it.key.date }
            .map { (pluginTrial, pluginSale) ->
                val testDuration = pluginTrial.date.daysUntil(pluginSale.date).absoluteValue
                SimpleDateTableRow(
                    values = mapOf(
                        trialDateColumn to pluginTrial.date,
                        licensedDateColumn to pluginSale.date,
                        testDurationColumn to testDuration.toBigInteger(),
                        customerColumn to LinkedCustomer(pluginTrial.customer.code, pluginId!!),
                    ),
                    sortValues = mapOf(
                        trialDateColumn to pluginTrial.date.sortValue,
                        licensedDateColumn to pluginSale.date.sortValue,
                        testDurationColumn to testDuration.absoluteValue.toLong(),
                    )
                )
            }

        val footer = SimpleRowGroup(
            SimpleDateTableRow(
                customerColumn to "${trialResult.convertedTrialsCount} converted trials"
            )
        )

        return listOf(SimpleTableSection(rows, footer = footer))
    }
}
