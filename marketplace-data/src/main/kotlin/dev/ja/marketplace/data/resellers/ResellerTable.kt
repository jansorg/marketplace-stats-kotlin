/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.resellers

import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.LicenseId
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.util.function.IntFunction

private data class ResellerTableRow(
    val resellerInfo: ResellerInfo,
    var licenses: MutableSet<LicenseId> = mutableSetOf(),
    var customers: MutableSet<CustomerId> = mutableSetOf(),
    var totalSales: Amount = Amount(0)
)

class ResellerTable : SimpleDataTable("Resellers", cssClass = "table-column-wide sortable"), MarketplaceDataSink {
    private val data = Int2ObjectOpenHashMap<ResellerTableRow>()

    private val columnCode = DataTableColumn("reseller-code", "Code")
    private val columnName = DataTableColumn("reseller-name", "Name")
    private val columnCountry = DataTableColumn("reseller-country", "Country")
    private val columnType = DataTableColumn("reseller-type", "Type")
    private val columnCustomerCount = DataTableColumn("reseller-customers", "Customers")
    private val columnLicenseCount = DataTableColumn("reseller-licenses", "Licenses Sold")
    private val columnTotalSales = DataTableColumn("reseller-sales", "Total Sales")

    override val columns: List<DataTableColumn> = listOf(
        columnName,
        columnCode,
        columnCountry,
        columnType,
        columnCustomerCount,
        columnLicenseCount,
        columnTotalSales,
    )

    override fun createSections(): List<DataTableSection> {
        val rows = data.int2ObjectEntrySet()
            .sortedByDescending { it.value.totalSales }
            .map { (_, row) ->
                SimpleDateTableRow(
                    columnName to row.resellerInfo.name,
                    columnCode to row.resellerInfo.code,
                    columnCountry to row.resellerInfo.country,
                    columnType to row.resellerInfo.type.displayString,
                    columnCustomerCount to row.customers.size,
                    columnLicenseCount to row.licenses.size,
                    columnTotalSales to row.totalSales.withCurrency(Currency.USD),
                )
            }

        val footer = SimpleDateTableRow(
            columnTotalSales to data.values.sumOf { it.totalSales }.withCurrency(Currency.USD),
            columnLicenseCount to data.values.sumOf { it.licenses.size },
            columnCustomerCount to data.values.sumOf { it.customers.size },
        )

        return listOf(SimpleTableSection(rows, footer = SimpleRowGroup(footer)))
    }

    override fun process(sale: PluginSale) {
        val resellerInfo = sale.reseller ?: return
        val row = data.computeIfAbsent(resellerInfo.code, IntFunction {
            ResellerTableRow(resellerInfo)
        })
        row.customers += sale.customer.code
    }

    override fun process(licenseInfo: LicenseInfo) {
        val reseller = licenseInfo.sale.reseller ?: return

        val row = data[reseller.code]!!
        row.licenses += licenseInfo.id
        row.totalSales += licenseInfo.amountUSD
    }
}