/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.resellers

import dev.ja.marketplace.client.CustomerId
import dev.ja.marketplace.client.PluginSale
import dev.ja.marketplace.client.ResellerInfo
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import dev.ja.marketplace.util.sortValue
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.util.function.IntFunction
import javax.money.MonetaryAmount

private data class ResellerTableRow(
    val resellerInfo: ResellerInfo,
    var totalSales: MonetaryAmountTracker,
    var licenses: MutableSet<LicenseId> = mutableSetOf(),
    var customers: MutableSet<CustomerId> = mutableSetOf(),
)

class ResellerTable : SimpleDataTable("Resellers", cssClass = "table-column-wide sortable"), MarketplaceDataSink {
    private val data = Int2ObjectOpenHashMap<ResellerTableRow>()
    private lateinit var totalSales: MonetaryAmountTracker

    private val columnName = DataTableColumn("reseller-name", "Name")
    private val columnTotalSales = DataTableColumn("reseller-sales", "Total Sales", "num", preSorted = AriaSortOrder.Descending)
    private val columnCustomerCount = DataTableColumn("reseller-customers", "Customers", "num")
    private val columnLicenseCount = DataTableColumn("reseller-licenses", "Licenses Sold", "num")
    private val columnCountry = DataTableColumn("reseller-country", "Country")
    private val columnType = DataTableColumn("reseller-type", "Type")
    private val columnCode = DataTableColumn("reseller-code", "Code")

    override val columns: List<DataTableColumn> = listOf(
        columnName,
        columnTotalSales,
        columnCustomerCount,
        columnLicenseCount,
        columnCountry,
        columnType,
        columnCode,
    )

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.totalSales = MonetaryAmountTracker(data.exchangeRates)
    }

    override suspend fun process(sale: PluginSale) {
        val resellerInfo = sale.reseller ?: return
        val row = data.computeIfAbsent(resellerInfo.code, IntFunction {
            ResellerTableRow(resellerInfo, MonetaryAmountTracker(exchangeRates))
        })
        row.customers += sale.customer.code
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        val reseller = licenseInfo.sale.reseller ?: return

        totalSales.add(licenseInfo.sale.date, licenseInfo.amountUSD, licenseInfo.amount)

        val row = data[reseller.code]!!
        row.licenses += licenseInfo.id
        row.totalSales.add(licenseInfo.sale.date, licenseInfo.amountUSD, licenseInfo.amount)
    }

    override suspend fun createSections(): List<DataTableSection> {
        val rows = data.int2ObjectEntrySet()
            .map { (_, row) ->
                SimpleDateTableRow(
                    columnName to row.resellerInfo.name,
                    columnCode to row.resellerInfo.code,
                    columnCountry to row.resellerInfo.country,
                    columnType to row.resellerInfo.type.displayString,
                    columnCustomerCount to row.customers.size,
                    columnLicenseCount to row.licenses.size,
                    columnTotalSales to row.totalSales.getTotalAmount(),
                )
            }
            .sortedByDescending { (it.values[columnTotalSales] as MonetaryAmount).sortValue() }

        val licenseCount = data.values.sumOf { it.licenses.size }
        val customerCount = data.values.sumOf { it.customers.size }
        val footer = SimpleDateTableRow(
            values = mapOf(
                columnTotalSales to totalSales.getTotalAmount(),
                columnLicenseCount to licenseCount.toBigInteger(),
                columnCustomerCount to customerCount.toBigInteger(),
            ),
            sortValues = mapOf(
                columnTotalSales to totalSales.getTotalAmount().sortValue(),
                columnLicenseCount to licenseCount.toLong(),
                columnCustomerCount to customerCount.toLong(),
            )
        )

        return listOf(SimpleTableSection(rows, footer = SimpleRowGroup(footer)))
    }
}