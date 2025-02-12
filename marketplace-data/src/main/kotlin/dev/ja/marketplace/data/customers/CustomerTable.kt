/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customers

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.CustomerInfo
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import dev.ja.marketplace.util.sortValue

data class CustomerTableRowData(
    val customer: CustomerInfo,
    var totalSales: MonetaryAmountTracker,
    var earliestLicenseStart: YearMonthDay? = null,
    var latestLicenseEnd: YearMonthDay? = null,
    val totalLicenses: MutableSet<LicenseId> = mutableSetOf(),
    val activeLicenses: MutableSet<LicenseId> = mutableSetOf()
)

class CustomerTable(
    private val customerFilter: (CustomerTableRowData) -> Boolean = { true },
    private val isChurnedStyling: Boolean = false,
    private val nowDate: YearMonthDay = YearMonthDay.now(),
) : SimpleDataTable("Customers", cssClass = "table-column-wide sortable"), MarketplaceDataSink {
    private val columnValidSince = DataTableColumn("customer-since", if (isChurnedStyling) "Licensed since" else "Since")
    private val columnValidUntil = DataTableColumn("customer-until", "Licensed Until")
    private val columnChurnedAt = DataTableColumn("customer-churn-date", "Churn date")
    private val columnName = DataTableColumn("customer-name", "Name", cssStyle = "width:20%")
    private val columnCountry = DataTableColumn("customer-country", "Country")
    private val columnType = DataTableColumn("customer-type", "Type")
    private val columnSales = DataTableColumn("sales-total", "Total Sales", "num")
    private val columnActiveLicenses = DataTableColumn("customer-licenses-active", "Active Licenses", "num")
    private val columnTotalLicenses = DataTableColumn("customer-licenses-total", "Total Licenses", "num")
    private val columnId = DataTableColumn("customer-id", "Cust. ID", "num")

    private val customerMap = mutableMapOf<CustomerId, CustomerTableRowData>()
    private lateinit var totalSales: MonetaryAmountTracker

    private var pluginId: PluginId? = null

    override val columns: List<DataTableColumn> = listOfNotNull(
        if (isChurnedStyling) columnChurnedAt else columnValidUntil,
        columnValidSince,
        columnName,
        columnSales,
        columnActiveLicenses.takeUnless { isChurnedStyling },
        columnTotalLicenses,
        columnType,
        columnCountry,
        columnId
    )

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.pluginId = data.pluginId
        this.totalSales = MonetaryAmountTracker(exchangeRates)
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        totalSales.add(licenseInfo.sale.date, licenseInfo.amountUSD, licenseInfo.amount)

        val customer = licenseInfo.sale.customer
        val data = customerMap.computeIfAbsent(customer.code) {
            CustomerTableRowData(customer, MonetaryAmountTracker(exchangeRates))
        }

        val validity = licenseInfo.validity
        if (validity != null) {
            val licenseStart = validity.start
            data.earliestLicenseStart = minOf(licenseStart, data.earliestLicenseStart ?: licenseStart)

            val licenseEnd = validity.end
            data.latestLicenseEnd = maxOf(licenseEnd, data.latestLicenseEnd ?: licenseEnd)

            // calculate active state of a license in the same way as LicenseTable
            if (validity.end >= nowDate) {
                data.activeLicenses += licenseInfo.id
            }
        }

        data.totalLicenses += licenseInfo.id
        data.totalSales.add(licenseInfo.sale.date, licenseInfo.amountUSD, licenseInfo.amount)
    }

    override suspend fun createSections(): List<DataTableSection> {
        var prevValidUntil: YearMonthDay? = null
        val displayedCustomers = customerMap.values
            .filter(customerFilter)
            .sortedByDescending { it.totalSales.getTotalAmount().sortValue() }
            .sortedByDescending { it.latestLicenseEnd!! }

        val rows = displayedCustomers
            .map { customerData ->
                val customer = customerData.customer
                val validSince = customerData.earliestLicenseStart!!
                val validUntil = customerData.latestLicenseEnd!!
                val churnedAt = validUntil.add(0, 0, 1)
                val showValidUntil = validUntil != prevValidUntil
                prevValidUntil = validUntil

                val cssClass: String? = when {
                    !isChurnedStyling && validUntil < nowDate -> "churned"
                    else -> null
                }

                SimpleDateTableRow(
                    mapOf(
                        when {
                            isChurnedStyling -> columnChurnedAt to churnedAt.takeIf { showValidUntil }
                            else -> columnValidUntil to validUntil.takeIf { showValidUntil }
                        },
                        columnValidSince to validSince,
                        columnId to LinkedCustomer(customer.code, pluginId = pluginId!!),
                        columnName to (customer.name ?: NoValue),
                        columnCountry to customer.country,
                        columnType to customer.type,
                        columnTotalLicenses to customerData.totalLicenses.size,
                        columnActiveLicenses to customerData.activeLicenses.size,
                        columnSales to customerData.totalSales.getTotalAmount(),
                    ),
                    cssClass = cssClass,
                    sortValues = mapOf(
                        columnValidUntil to validUntil.sortValue,
                        columnValidSince to validSince.sortValue,
                        columnSales to customerData.totalSales.getTotalAmount().sortValue(),
                    ),
                )
            }

        val footer = SimpleRowGroup(
            SimpleDateTableRow(
                columnName to "${displayedCustomers.size} customers",
                columnTotalLicenses to displayedCustomers.sumOf { it.totalLicenses.size }.toBigInteger(),
                columnActiveLicenses to displayedCustomers.sumOf { it.activeLicenses.size }.toBigInteger(),
                columnSales to totalSales.getTotalAmount(),
            )
        )

        return listOf(SimpleTableSection(rows, footer = footer))
    }
}
