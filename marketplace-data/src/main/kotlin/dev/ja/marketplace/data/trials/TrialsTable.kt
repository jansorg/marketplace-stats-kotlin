/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trials

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.*
import dev.ja.marketplace.util.takeNullable
import java.util.*

class TrialsTable(
    private val maxTableRows: Int? = null,
    private val showDetails: Boolean = true,
    private val trialFilter: (PluginTrial) -> Boolean = { true },
) : SimpleDataTable("Trials", "trials", "table-centered section-medium"), MarketplaceDataSink {
    private val columnDate = DataTableColumn("trial-date", "Date", "date")
    private val columnRefId = DataTableColumn("trial-id", "ID", "num")
    private val columnCustomer = DataTableColumn("trial-customer", "Customer")
    private val columnCustomerType = DataTableColumn("trial-type", "Type")
    private val columnCustomerCountry = DataTableColumn("trial-country", "Country")

    private val trialData = TreeMap<YearMonthDay, List<PluginTrial>>()

    private var pluginId: PluginId? = null

    override val isLimitedRendering: Boolean
        get() {
            return maxTableRows != null
        }

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.pluginId = data.pluginId
        data.getTrials()?.forEach {
            if (trialFilter(it)) {
                trialData.merge(it.date, listOf(it)) { a, b -> a + b }
            }
        }
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        // empty
    }

    override val columns: List<DataTableColumn> = listOfNotNull(
        columnDate,
        columnCustomer.takeIf { showDetails },
        columnCustomerType.takeIf { showDetails },
        columnCustomerCountry.takeIf { showDetails },
        columnRefId
    )

    override suspend fun createSections(): List<DataTableSection> {
        val today = YearMonthDay.now()

        val rows = mutableListOf<SimpleDateTableRow>()
        loop@ for ((day, trials) in trialData.entries.reversed().takeNullable(maxTableRows)) {
            var first = true
            for (trial in trials) {
                rows += SimpleDateTableRow(
                    values = mapOf(
                        columnDate to if (first) day else null,
                        columnRefId to trial.referenceId,
                        columnCustomer to LinkedCustomer(trial.customer.code, pluginId = pluginId!!),
                        columnCustomerType to trial.customer.type,
                        columnCustomerCountry to trial.customer.country.takeIf(String::isNotBlank),
                    ),
                    htmlId = if (day == today) "today" else null,
                )
                first = false
                if (maxTableRows != null && rows.size >= maxTableRows) {
                    break@loop
                }
            }
        }
        return listOf(SimpleTableSection(rows))
    }
}
