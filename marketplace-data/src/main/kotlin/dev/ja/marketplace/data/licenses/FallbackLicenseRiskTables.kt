/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.model.CustomerInfo
import dev.ja.marketplace.data.*
import dev.ja.marketplace.util.sortValue

private fun linkedCustomer(customer: CustomerInfo, pluginId: PluginId): Any {
    // most customers have no name, their ID is the link
    return customer.name?.let { LinkedCustomerName(it, customer.code, pluginId) } ?: LinkedCustomer(customer.code, pluginId)
}

private val columnAnnualRevenue = DataTableColumn(
    "fallback-annual-revenue", "ARR at Risk", "num",
    tooltip = "The latest purchase of the licenses, normalized to a year, which is lost if they aren't renewed"
)

/**
 * The customers of the licenses, whose fallback version is the latest version of the plugin, see [FallbackLicenseRisk].
 */
class FallbackCustomersTable(
    private val risk: FallbackLicenseRisk,
    private val pluginId: PluginId,
) : SimpleDataTable("Customers", "fallback-customers", "table-column-wide sortable") {
    private val columnCustomer = DataTableColumn("customer", "Customer", cssStyle = "width: 20%")
    private val columnCountry = DataTableColumn("customer-country", "Country")
    private val columnType = DataTableColumn("customer-type", "Type")
    private val columnLicenses = DataTableColumn("customer-licenses", "Licenses", "num")
    private val columnFirstEnd = DataTableColumn(
        "customer-first-end", "First End", "date",
        tooltip = "The end of the subscription of the customer, which ends first"
    )
    // the rows are ordered by the revenue at risk
    private val columnRevenue = columnAnnualRevenue.copy(preSorted = AriaSortOrder.Descending)

    override val columns: List<DataTableColumn> = listOf(
        columnCustomer, columnCountry, columnType, columnLicenses, columnFirstEnd, columnRevenue,
    )

    override suspend fun process(licenseInfo: LicenseInfo) {}

    override suspend fun createSections(): List<DataTableSection> {
        val rows = risk.affectedCustomers.map { customer ->
            SimpleDateTableRow(
                values = mapOf(
                    columnCustomer to linkedCustomer(customer.customer, pluginId),
                    columnCountry to customer.customer.country,
                    columnType to customer.customer.type,
                    columnLicenses to customer.licenses.size.toBigInteger(),
                    columnFirstEnd to customer.firstSubscriptionEnd,
                    columnRevenue to customer.annualRevenue,
                ),
                sortValues = mapOf(
                    columnFirstEnd to customer.firstSubscriptionEnd.sortValue,
                    columnRevenue to customer.annualRevenue.sortValue(),
                ),
            )
        }

        val footer = SimpleRowGroup(
            SimpleDateTableRow(
                columnCustomer to "${risk.affectedCustomers.size} customers",
                columnLicenses to risk.affectedLicenses.size.toBigInteger(),
                columnRevenue to risk.revenueAtRisk,
            )
        )
        return listOf(SimpleTableSection(rows, footer = footer))
    }
}

/**
 * The licenses, whose fallback version is the latest version of the plugin, see [FallbackLicenseRisk].
 */
class FallbackLicensesTable(
    private val risk: FallbackLicenseRisk,
    private val pluginId: PluginId,
) : SimpleDataTable("Licenses", "fallback-licenses", "table-column-wide sortable") {
    // the rows are ordered by the end of the subscription
    private val columnEnd = DataTableColumn(
        "license-validity-end", "End", "date", preSorted = AriaSortOrder.Ascending,
        tooltip = "The end of the subscription, the license has to be renewed by then"
    )
    private val columnStart = DataTableColumn(
        "license-validity", "Subscribed Since", "date",
        tooltip = "The start of the latest subscription without interruptions"
    )
    private val columnFallbackVersion = DataTableColumn(
        "license-fallback-version", "Fallback Version", "num",
        tooltip = "The version, which the perpetual fallback license covers, if the license isn't renewed"
    )
    private val columnCustomer = DataTableColumn("customer", "Customer", cssStyle = "width: 20%")
    private val columnPeriod = DataTableColumn("license-period", "Period")
    private val columnAmount = DataTableColumn(
        "sale-amount", "Latest Purchase", "num", tooltip = "The amount of the latest purchase of the license"
    )
    private val columnLicenseId = DataTableColumn("license-id", "License ID", "col-right")
    private val columnRefNum = DataTableColumn("license-refnum", "Ref Num", "col-right")

    override val columns: List<DataTableColumn> = listOf(
        columnEnd, columnStart, columnFallbackVersion, columnCustomer, columnPeriod, columnAmount, columnAnnualRevenue,
        columnLicenseId, columnRefNum,
    )

    override suspend fun process(licenseInfo: LicenseInfo) {}

    override suspend fun createSections(): List<DataTableSection> {
        val rows = risk.affectedLicenses.map { license ->
            val purchase = license.latestPurchase
            val amount = purchase.renderAmount(purchase.sale.date)
            SimpleDateTableRow(
                values = mapOf(
                    columnEnd to license.fallbackVersion.subscriptionEnd,
                    columnStart to license.fallbackVersion.subscriptionStart,
                    columnFallbackVersion to license.fallbackVersion,
                    columnCustomer to linkedCustomer(license.customer, pluginId),
                    columnPeriod to license.period,
                    columnAmount to amount,
                    columnAnnualRevenue to license.annualRevenue,
                    columnLicenseId to LinkedLicense(license.licenseId, pluginId),
                    columnRefNum to LinkedRefNum(purchase.sale.ref, pluginId),
                ),
                sortValues = mapOf(
                    columnEnd to license.fallbackVersion.subscriptionEnd.sortValue,
                    columnStart to license.fallbackVersion.subscriptionStart.sortValue,
                    columnAmount to amount.sortValue(),
                    columnAnnualRevenue to license.annualRevenue.sortValue(),
                ),
            )
        }

        val footer = SimpleRowGroup(
            SimpleDateTableRow(
                columnCustomer to "${risk.affectedCustomers.size} customers",
                columnAnnualRevenue to risk.revenueAtRisk,
                columnLicenseId to "${risk.affectedLicenses.size} licenses",
            )
        )
        return listOf(SimpleTableSection(rows, footer = footer))
    }
}
