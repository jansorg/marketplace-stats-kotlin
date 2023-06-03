/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customers

import dev.ja.marketplace.client.Amount
import dev.ja.marketplace.client.Currency
import dev.ja.marketplace.client.CustomerInfo
import dev.ja.marketplace.data.*
import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.LicenseId

class CustomerTable(val licenseFilter: (LicenseInfo) -> Boolean) :
    SimpleDataTable("Customers", cssClass = "section-wide"), MarketplaceDataSink {
    private val customers = mutableSetOf<CustomerInfo>()
    private val latestLicenseValid = mutableMapOf<CustomerInfo, YearMonthDay>()
    private val customerSalesActive = mutableMapOf<CustomerInfo, Amount>()
    private val customerSalesNext = mutableMapOf<CustomerInfo, Amount>()
    private val customerSales = mutableMapOf<CustomerInfo, Amount>()
    private val activeLicenses = mutableMapOf<CustomerInfo, MutableSet<LicenseId>>()

    private val columnValidUntil = DataTableColumn("customer-type", "Valid Until")
    private val columnName = DataTableColumn("customer-name", "Name")
    private val columnCountry = DataTableColumn("customer-country", "Country")
    private val columnType = DataTableColumn("customer-type", "Type")
    private val columnActiveSales = DataTableColumn("sales-active", "Act. Sales", "num")
    private val columnNextSale = DataTableColumn("sales-next", "Next Sale", "num")
    private val columnSales = DataTableColumn("sales-total", "Total Sales", "num")
    private val columnActiveLicenses = DataTableColumn("customer-licenses", "Active Licenses", "num")
    private val columnId = DataTableColumn("customer-id", "Cust. ID", "num")

    private val salesCalculator = SaleCalculator()

    override val columns: List<DataTableColumn> = listOf(
        columnValidUntil,
        columnName,
        columnCountry,
        columnType,
        columnActiveSales,
        columnNextSale,
        columnSales,
        columnActiveLicenses,
        columnId
    )

    override fun process(licenseInfo: LicenseInfo) {
        val customer = licenseInfo.sale.customer

        if (YearMonthDay.now() in licenseInfo.dateRange) {
            customerSalesActive.merge(customer, licenseInfo.amountUSD) { a, b -> a + b }
            customerSalesNext.merge(customer, licenseInfo.amountUSD) { a, _ ->
                a + salesCalculator.nextSale(licenseInfo).amountUSD
            }
        }

        customerSales.merge(customer, licenseInfo.amountUSD) { a, b -> a + b }
        latestLicenseValid.merge(customer, licenseInfo.validity.end, ::maxOf)
        activeLicenses.computeIfAbsent(customer) { mutableSetOf() } += licenseInfo.id

        if (licenseFilter(licenseInfo)) {
            customers += customer
        }
    }

    override val sections: List<DataTableSection>
        get() {
            val sortedByValidity = customers.sortedBy { latestLicenseValid[it]!! }
            val now = YearMonthDay.now()
            val rows = sortedByValidity.map { customer ->
                val latestValid = latestLicenseValid[customer]!!
                val cssClass: String? = when {
                    latestValid < now -> "churned"
                    else -> null
                }
                SimpleDateTableRow(
                    mapOf(
                        columnId to customer.code,
                        columnName to customer.name,
                        columnCountry to customer.country,
                        columnType to customer.type,
                        columnActiveSales to customerSalesActive[customer]?.withCurrency(Currency.USD),
                        columnNextSale to customerSalesNext[customer]?.withCurrency(Currency.USD),
                        columnSales to customerSales[customer]?.withCurrency(Currency.USD),
                        columnActiveLicenses to activeLicenses[customer]!!.size,
                        columnValidUntil to latestValid
                    ),
                    cssClass = cssClass,
                    sortValues = mapOf(
                        columnActiveSales to customerSalesActive[customer]?.sortValue(),
                        columnNextSale to customerSalesNext[customer]?.sortValue(),
                        columnSales to customerSales[customer]?.sortValue(),
                    ),
                )
            }
            val footer = SimpleRowGroup(SimpleDateTableRow(columnName to "${sortedByValidity.size} customers"))
            return listOf(SimpleTableSection(rows, footer = footer))
        }
}
