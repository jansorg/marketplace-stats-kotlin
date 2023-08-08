/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customers

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.data.*

class CustomerTable(
    val licenseFilter: (LicenseInfo) -> Boolean = { true },
    val customerFilter: (CustomerInfo, latestValid: YearMonthDay) -> Boolean = { _, _ -> true },
    private val showChurnedStyling: Boolean = true,
) :
    SimpleDataTable("Customers", cssClass = "section-wide"), MarketplaceDataSink {
    private val customers = mutableSetOf<CustomerInfo>()
    private val latestLicenseValid = mutableMapOf<CustomerInfo, YearMonthDay>()
    private val customerSalesActive = mutableMapOf<CustomerInfo, Amount>()
    private val customerSalesNext = mutableMapOf<CustomerInfo, Amount>()
    private val totalCustomerSales = mutableMapOf<CustomerInfo, Amount>()
    private val activeLicenses = mutableMapOf<CustomerInfo, MutableSet<LicenseId>>()

    private val columnValidUntil = DataTableColumn("customer-type", "Valid Until")
    private val columnName = DataTableColumn("customer-name", "Name")
    private val columnCountry = DataTableColumn("customer-country", "Country")
    private val columnType = DataTableColumn("customer-type", "Type")
    private val columnActiveSales = DataTableColumn("sales-active", "Last Sale", "num")
    private val columnNextSale = DataTableColumn("sales-next", "Next Sale", "num")
    private val columnSales = DataTableColumn("sales-total", "Total Sales", "num")
    private val columnActiveLicenses = DataTableColumn("customer-licenses", "Licenses", "num")
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

        totalCustomerSales.merge(customer, licenseInfo.amountUSD) { a, b -> a + b }

        if (licenseFilter(licenseInfo)) {
            customers += customer
            activeLicenses.computeIfAbsent(customer) { mutableSetOf() } += licenseInfo.id
            latestLicenseValid.merge(customer, licenseInfo.validity.end, ::maxOf)
        }
    }

    override val sections: List<DataTableSection>
        get() {
            val now = YearMonthDay.now()
            var prevValidUntil: YearMonthDay? = null
            val rows = customers
                .filter { customerFilter(it, latestLicenseValid[it]!!) }
                .sortedByDescending { totalCustomerSales[it]?.sortValue() ?: 0L }
                .sortedByDescending { latestLicenseValid[it]!! }
                .map { customer ->
                    val validUntil = latestLicenseValid[customer]!!
                    val showValidUntil = validUntil != prevValidUntil
                    prevValidUntil = validUntil

                    val cssClass: String? = when {
                        validUntil < now && showChurnedStyling -> "churned"
                        else -> null
                    }

                    SimpleDateTableRow(
                        mapOf(
                            columnValidUntil to if (showValidUntil) validUntil else null,
                            columnId to customer.code,
                            columnName to customer.name,
                            columnCountry to customer.country,
                            columnType to customer.type,
                            columnActiveSales to customerSalesActive[customer]?.withCurrency(Currency.USD),
                            columnNextSale to customerSalesNext[customer]?.withCurrency(Currency.USD),
                            columnSales to totalCustomerSales[customer]?.withCurrency(Currency.USD),
                            columnActiveLicenses to activeLicenses[customer]!!.size,
                        ),
                        cssClass = cssClass,
                        sortValues = mapOf(
                            columnActiveSales to customerSalesActive[customer]?.sortValue(),
                            columnNextSale to customerSalesNext[customer]?.sortValue(),
                            columnSales to totalCustomerSales[customer]?.sortValue(),
                        ),
                    )
                }
            val footer = SimpleRowGroup(
                SimpleDateTableRow(
                    columnName to "${rows.size} customers",
                    columnActiveLicenses to rows.size,
                )
            )
            return listOf(SimpleTableSection(rows, footer = footer))
        }
}
