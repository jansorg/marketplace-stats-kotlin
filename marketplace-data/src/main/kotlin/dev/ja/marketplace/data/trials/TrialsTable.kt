/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trials

import dev.ja.marketplace.client.Country
import dev.ja.marketplace.client.PluginTrial
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.*
import java.util.*

class TrialsTable : SimpleDataTable("Trials", "trials"), MarketplaceDataSink {
    private val columnDate = DataTableColumn("trial-date", "Date", "date")
    private val columnRefId = DataTableColumn("trial-id", "ID", "num")
    private val columnCustomer = DataTableColumn("trial-customer", "Customer")
    private val columnCustomerType = DataTableColumn("trial-type", "Type")
    private val columnCustomerCountry = DataTableColumn("trial-country", "Country")

    private val trialData = TreeMap<YearMonthDay, List<PluginTrial>>()

    override fun init(data: PluginData) {
        data.trials.forEach {
            trialData.merge(it.date, listOf(it)) { a, b -> a + b }
        }
    }

    override fun process(licenseInfo: LicenseInfo) {
        // empty
    }

    override val columns: List<DataTableColumn> = listOf(
        columnDate,
        columnCustomer,
        columnCustomerType,
        columnCustomerCountry,
        columnRefId
    )

    override val sections: List<DataTableSection>
        get() {
            val today = YearMonthDay.now()

            val rows = mutableListOf<SimpleDateTableRow>()
            for ((day, trials) in trialData.entries.reversed()) {
                var first = true
                for (trial in trials) {
                    rows += SimpleDateTableRow(
                        values = mapOf(
                            columnDate to if (first) day else null,
                            columnRefId to trial.referenceId,
                            columnCustomer to trial.customer.name,
                            columnCustomerType to trial.customer.type,
                            columnCustomerCountry to trial.customer.country.takeIf(Country::isNotBlank),
                        ),
                        htmlId = if (day == today) "today" else null,
                    )

                    first = false
                }
            }
            return listOf(SimpleTableSection(rows))
        }
}
