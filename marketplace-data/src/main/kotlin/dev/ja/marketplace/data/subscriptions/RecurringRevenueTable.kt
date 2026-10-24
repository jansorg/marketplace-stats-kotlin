/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.data.ChartPeriod
import dev.ja.marketplace.data.DataTableSection
import dev.ja.marketplace.data.IncompleteValue
import dev.ja.marketplace.data.PluginData
import dev.ja.marketplace.data.SimpleDataTable

/**
 * Base class of the tables, which are calculated from the recurring revenue of each customer.
 */
abstract class RecurringRevenueTable(
    title: String,
    id: String,
    cssClass: String? = null,
) : SimpleDataTable(title, id, cssClass) {
    private val licenses = mutableListOf<LicenseInfo>()
    private lateinit var data: PluginData

    override val isChartable: Boolean = true
    override val chartPeriod: ChartPeriod = ChartPeriod.Month

    override suspend fun init(data: PluginData) {
        super.init(data)
        this.data = data
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        licenses += licenseInfo
    }

    override suspend fun createSections(): List<DataTableSection> {
        // The tables of a page process all licenses and share the result. MCP tools may only process some licenses.
        val revenue = when (licenses.size) {
            data.getLicenses()?.size -> data.getCustomerRecurringRevenue()
            else -> CustomerRecurringRevenue(licenses, exchangeRates)
        }
        return createSections(revenue)
    }

    protected abstract fun createSections(revenue: CustomerRecurringRevenue): List<DataTableSection>

    /**
     * Values of the current month may still change.
     */
    protected fun CustomerRecurringRevenue.valueOf(monthIndex: Int, value: Any?): Any? {
        return when {
            value == null -> null
            isCurrentMonth(monthIndex) -> IncompleteValue(value)
            else -> value
        }
    }
}
