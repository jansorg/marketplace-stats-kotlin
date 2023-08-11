/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customers

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.data.*

data class CustomerTableRowData(
    val customer: CustomerInfo,
    var earliestLicenseStart: YearMonthDay? = null,
    var latestLicenseEnd: YearMonthDay? = null,
    val totalLicenses: MutableSet<LicenseId> = mutableSetOf(),
    val activeLicenses: MutableSet<LicenseId> = mutableSetOf(),
    var lastSaleUSD: Amount = Amount.ZERO,
    var nextSaleUSD: Amount = Amount.ZERO,
    var totalSalesUSD: Amount = Amount.ZERO,
)

class CustomerTable(
    private val customerFilter: (CustomerTableRowData) -> Boolean = { true },
    private val isChurnedStyling: Boolean = false,
    private val nowDate: YearMonthDay = YearMonthDay.now(),
) : SimpleDataTable("Customers", cssClass = "section-wide"), MarketplaceDataSink {
    private val columnValidSince = DataTableColumn("customer-since", "Since")
    private val columnValidUntil = DataTableColumn("customer-until", "Valid Until")
    private val columnName = DataTableColumn("customer-name", "Name", cssStyle = "width:20%")
    private val columnCountry = DataTableColumn("customer-country", "Country")
    private val columnType = DataTableColumn("customer-type", "Type")
    private val columnLastSales = DataTableColumn("sales-active", "Last Sale", "num")
    private val columnNextSale = DataTableColumn("sales-next", "Next Sale", "num")
    private val columnSales = DataTableColumn("sales-total", "Total Sales", "num")
    private val columnActiveLicenses = DataTableColumn("customer-licenses-active", "Active Licenses", "num")
    private val columnTotalLicenses = DataTableColumn("customer-licenses-total", "Total Licenses", "num")
    private val columnId = DataTableColumn("customer-id", "Cust. ID", "num")

    private val customerMap = mutableMapOf<CustomerId, CustomerTableRowData>()
    private val salesCalculator = SaleCalculator()

    override val columns: List<DataTableColumn> = listOfNotNull(
        columnValidUntil,
        columnValidSince,
        columnName,
        columnSales,
        columnLastSales.takeUnless { isChurnedStyling },
        columnNextSale.takeUnless { isChurnedStyling },
        columnActiveLicenses.takeUnless { isChurnedStyling },
        columnTotalLicenses,
        columnType,
        columnCountry,
        columnId
    )

    override fun process(licenseInfo: LicenseInfo) {
        val customer = licenseInfo.sale.customer
        val data = customerMap.computeIfAbsent(customer.code) { CustomerTableRowData(customer) }

        val licenseStart = licenseInfo.validity.start
        data.earliestLicenseStart = minOf(licenseStart, data.earliestLicenseStart ?: licenseStart)

        val licenseEnd = licenseInfo.validity.end
        data.latestLicenseEnd = maxOf(licenseEnd, data.latestLicenseEnd ?: licenseEnd)

        data.totalLicenses += licenseInfo.id
        if (nowDate in licenseInfo.validity) {
            data.activeLicenses += licenseInfo.id
        }
        data.totalSalesUSD += licenseInfo.amountUSD
        data.lastSaleUSD += licenseInfo.amountUSD
        data.nextSaleUSD += salesCalculator.nextSale(licenseInfo).amountUSD
    }

    override val sections: List<DataTableSection>
        get() {
            val now = YearMonthDay.now()
            var prevValidUntil: YearMonthDay? = null
            val displayedCustomers = customerMap.values
                .filter(customerFilter)
                .sortedByDescending { it.totalSalesUSD.sortValue() }
                .sortedByDescending { it.latestLicenseEnd!! }

            /*for (row in displayedCustomers) {
                if (row.activeLicenses != row.totalLicenses) {
                    println("c: ${row.customer}")
                }
            }*/

            val rows = displayedCustomers
                .map { customerData ->
                    val customer = customerData.customer
                    val validSince = customerData.earliestLicenseStart!!
                    val validUntil = customerData.latestLicenseEnd!!
                    val showValidUntil = validUntil != prevValidUntil
                    prevValidUntil = validUntil

                    val cssClass: String? = when {
                        !isChurnedStyling && validUntil < now -> "churned"
                        else -> null
                    }

                    SimpleDateTableRow(
                        mapOf(
                            columnValidUntil to if (showValidUntil) validUntil else null,
                            columnValidSince to validSince,
                            columnId to customer.code,
                            columnName to customer.name,
                            columnCountry to customer.country,
                            columnType to customer.type,
                            columnTotalLicenses to customerData.totalLicenses.size,
                            columnActiveLicenses to customerData.activeLicenses.size,
                            columnSales to customerData.totalSalesUSD.withCurrency(Currency.USD),
                            columnLastSales to customerData.lastSaleUSD
                                .takeUnless { isChurnedStyling }
                                ?.withCurrency(Currency.USD),
                            columnNextSale to customerData.nextSaleUSD
                                .takeUnless { isChurnedStyling }
                                ?.withCurrency(Currency.USD),
                        ),
                        cssClass = cssClass,
                        sortValues = mapOf(
                            columnLastSales to customerData.lastSaleUSD.sortValue(),
                            columnNextSale to customerData.nextSaleUSD.sortValue(),
                            columnSales to customerData.totalSalesUSD.sortValue(),
                        ),
                    )
                }

            val footer = SimpleRowGroup(
                SimpleDateTableRow(
                    columnName to "${displayedCustomers.size} customers",
                    columnTotalLicenses to displayedCustomers.sumOf { it.totalLicenses.size },
                    columnActiveLicenses to displayedCustomers.sumOf { it.activeLicenses.size },
                    columnSales to displayedCustomers.sumOf { it.totalSalesUSD }.withCurrency(Currency.USD),
                    // fixme: unreliable because of future licenses, etc.
                    //columnNextSale to displayedCustomers.sumOf { it.nextSaleUSD }.withCurrency(Currency.USD),
                )
            )

            return listOf(SimpleTableSection(rows, footer = footer))
        }
}
