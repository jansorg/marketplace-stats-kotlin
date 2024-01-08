/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.funnel

import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.PluginSale
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.util.SimpleTrialTracker
import dev.ja.marketplace.data.util.TrialTracker
import kotlin.math.absoluteValue

class FunnelTable : SimpleDataTable("Trial Funnel", "funnel", "table-centered"), MarketplaceDataSink {
    private var pluginId: PluginId? = null
    private val trialTracker: TrialTracker = SimpleTrialTracker()

    private val trialDateColumn = DataTableColumn("funnel-trial", "Trial", "date")
    private val licensedDateColumn = DataTableColumn("funnel-license", "Licensed", "date")
    private val testDurationColumn = DataTableColumn("funnel-test-duration", "Test Period", "num")
    private val customerColumn = DataTableColumn("funnel-customer", "Customer", "num")

    override val columns: List<DataTableColumn> = listOf(trialDateColumn, licensedDateColumn, testDurationColumn, customerColumn)

    override fun init(data: PluginData) {
        this.pluginId = data.pluginId
        if (data.trials != null) {
            data.trials.forEach(trialTracker::registerTrial)
        }
    }

    override fun process(sale: PluginSale) {
        trialTracker.processSale(sale)
    }

    override fun process(licenseInfo: LicenseInfo) {
        // empty
    }

    override fun createSections(): List<DataTableSection> {
        val trialResult = trialTracker.getResult()
        val convertedTrials = trialResult.convertedTrials
        val rows = convertedTrials.entries
            .sortedByDescending { it.key.date }
            .map { (pluginTrial, pluginSale) ->
                SimpleDateTableRow(
                    trialDateColumn to pluginTrial.date,
                    licensedDateColumn to pluginSale.date,
                    testDurationColumn to pluginTrial.date.daysUntil(pluginSale.date).absoluteValue,
                    customerColumn to LinkedCustomer(pluginTrial.customer.code, pluginId!!),
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
