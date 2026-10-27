/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customers

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.CustomerInfo
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.licenses.LicensePurchases
import dev.ja.marketplace.data.licenses.LicenseStatus
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import dev.ja.marketplace.util.sortValue
import dev.ja.marketplace.util.takeNullable
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet

data class CustomerTableRowData(
    val customer: CustomerInfo,
    var totalSales: MonetaryAmountTracker,
    var earliestLicenseStart: YearMonthDay? = null,
    var latestLicenseEnd: YearMonthDay? = null,
    val totalLicenses: MutableSet<LicenseId> = ObjectOpenHashSet(),
    /** Licenses, which this customer holds today. */
    val activeLicenses: MutableSet<LicenseId> = ObjectOpenHashSet(),
    /** Licenses with a subscription, the status of a perpetual license doesn't change. */
    val subscriptionLicenses: MutableSet<LicenseId> = ObjectOpenHashSet(),
)

/**
 * The customers with the numbers of their licenses.
 *
 * Customers aren't churned, only licenses: a license can be renewed by another customer, e.g. after it was transferred
 * to an organization. A license is churned, if the latest purchase of all customers ended. A license of a customer is
 * transferred, if another customer renewed it, and the license is still valid.
 */
class CustomerTable(
    private val nowDate: YearMonthDay = YearMonthDay.now(),
    /** The maximum number of shown rows, all rows if it's `null`. The footer contains all customers. */
    private val maxTableRows: Int? = null,
    /** Only the customers, which match the filter, e.g. the customers of an offer, `null` for all customers. */
    private val customerFilter: ((CustomerId) -> Boolean)? = null,
) : SimpleDataTable("Customers", cssClass = "table-column-wide sortable"), MarketplaceDataSink {
    private val columnValidSince = DataTableColumn("customer-since", "Since")
    // the rows are sorted by the end of the latest license, the sortable table shows its arrow
    private val columnValidUntil = DataTableColumn(
        "customer-until", "Licensed Until", preSorted = AriaSortOrder.Descending,
        tooltip = "End of the latest purchase of the customer"
    )
    private val columnName = DataTableColumn("customer-name", "Name", cssStyle = "width:20%")
    private val columnCountry = DataTableColumn("customer-country", "Country")
    private val columnType = DataTableColumn("customer-type", "Type")
    private val columnSales = DataTableColumn("sales-total", "Total Sales", "num")
    private val columnActiveLicenses = DataTableColumn(
        "customer-licenses-active", "Active", "num", tooltip = "Licenses, which the customer holds today"
    )
    private val columnChurnedLicenses = DataTableColumn(
        "customer-licenses-churned", "Churned", "num",
        tooltip = "Licenses, which ended and weren't renewed by any customer"
    )
    private val columnTransferredLicenses = DataTableColumn(
        "customer-licenses-transferred", "Transferred", "num",
        tooltip = "Licenses, which another customer renewed and still holds, e.g. after a transfer to an organization"
    )
    private val columnTotalLicenses = DataTableColumn("customer-licenses-total", "Total Licenses", "num")
    private val columnId = DataTableColumn("customer-id", "Cust. ID", "num")

    // linked map to keep the insertion order, which is the order of customers with equal sort values
    private val customerMap = Int2ObjectLinkedOpenHashMap<CustomerTableRowData>()
    // the purchases of all customers, a license renewed by another customer isn't churned
    private val allPurchases = LicensePurchases()
    private lateinit var totalSales: MonetaryAmountTracker

    private var pluginId: PluginId? = null

    // the column of transferred licenses is only shown, if a customer has one
    override val columns: List<DataTableColumn>
        get() = listOfNotNull(
            columnValidUntil,
            columnValidSince,
            columnName,
            columnSales,
            columnTotalLicenses,
            columnActiveLicenses,
            columnTransferredLicenses.takeIf { customerMap.values.any { transferredLicenses(it) > 0 } },
            columnChurnedLicenses,
            columnType,
            columnCountry,
            columnId
        )

    override val isLimitedRendering: Boolean
        get() = maxTableRows != null

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.pluginId = data.pluginId
        this.totalSales = MonetaryAmountTracker(exchangeRates)
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        allPurchases.add(licenseInfo)
        totalSales.add(licenseInfo.sale.date, licenseInfo.amountUSD, licenseInfo.amount)

        val customer = licenseInfo.sale.customer
        val data = customerMap.computeIfAbsent(customer.code) {
            CustomerTableRowData(customer, MonetaryAmountTracker(exchangeRates))
        }

        val validity = licenseInfo.validity
        // a perpetual license starts at its sale, it doesn't end
        val licenseStart = validity?.start ?: licenseInfo.sale.date
        data.earliestLicenseStart = minOf(licenseStart, data.earliestLicenseStart ?: licenseStart)
        if (validity == null) {
            data.activeLicenses += licenseInfo.id
        } else {
            val licenseEnd = validity.end
            data.latestLicenseEnd = maxOf(licenseEnd, data.latestLicenseEnd ?: licenseEnd)

            // calculate active state of a license in the same way as LicenseTable
            if (validity.end >= nowDate) {
                data.activeLicenses += licenseInfo.id
            }

            data.subscriptionLicenses += licenseInfo.id
        }

        data.totalLicenses += licenseInfo.id
        data.totalSales.add(licenseInfo.sale.date, licenseInfo.amountUSD, licenseInfo.amount)
    }

    private fun churnedLicenses(data: CustomerTableRowData): Int {
        return data.subscriptionLicenses.count { allPurchases.status(it, nowDate) == LicenseStatus.Churned }
    }

    private fun transferredLicenses(data: CustomerTableRowData): Int {
        return data.subscriptionLicenses.count { it !in data.activeLicenses && allPurchases.status(it, nowDate) == LicenseStatus.Active }
    }

    override suspend fun createSections(): List<DataTableSection> {
        var prevValidUntil: YearMonthDay? = null
        // customers with only perpetual licenses have no end, they're shown last
        val displayedCustomers = customerMap.values
            .filter { customerFilter?.invoke(it.customer.code) ?: true }
            .sortedByDescending { it.totalSales.getTotalAmount().sortValue() }
            .sortedByDescending { it.latestLicenseEnd ?: YearMonthDay.MIN }

        val rows = displayedCustomers
            .takeNullable(maxTableRows)
            .map { customerData ->
                val customer = customerData.customer
                val validSince = customerData.earliestLicenseStart!!
                val validUntil = customerData.latestLicenseEnd
                val showValidUntil = validUntil != prevValidUntil
                prevValidUntil = validUntil

                SimpleDateTableRow(
                    mapOf(
                        columnValidUntil to (validUntil ?: NoValue).takeIf { showValidUntil },
                        columnValidSince to validSince,
                        // the ID and the name link to the customer
                        columnId to LinkedCustomer(customer.code, pluginId = pluginId!!),
                        columnName to (customer.name?.let { LinkedCustomerName(it, customer.code, pluginId!!) } ?: NoValue),
                        columnCountry to customer.country,
                        columnType to customer.type,
                        columnTotalLicenses to customerData.totalLicenses.size.toBigInteger(),
                        columnActiveLicenses to customerData.activeLicenses.size.toBigInteger(),
                        columnChurnedLicenses to churnedLicenses(customerData).toBigInteger(),
                        columnTransferredLicenses to transferredLicenses(customerData).toBigInteger(),
                        columnSales to customerData.totalSales.getTotalAmount(),
                    ),
                    sortValues = mapOf(
                        columnValidUntil to (validUntil?.sortValue ?: Long.MIN_VALUE),
                        columnValidSince to validSince.sortValue,
                        columnSales to customerData.totalSales.getTotalAmount().sortValue(),
                    ),
                )
            }

        val footer = SimpleRowGroup(
            SimpleDateTableRow(
                columnName to "${displayedCustomers.size} customers",
                // a license of several customers, e.g. after a transfer, is counted once
                columnTotalLicenses to displayedCustomers.flatMapTo(HashSet()) { it.totalLicenses }.size.toBigInteger(),
                columnActiveLicenses to displayedCustomers.flatMapTo(HashSet()) { it.activeLicenses }.size.toBigInteger(),
                columnChurnedLicenses to when (customerFilter) {
                    null -> allPurchases.licenseIds.count { allPurchases.status(it, nowDate) == LicenseStatus.Churned }
                    else -> displayedCustomers.flatMapTo(HashSet()) { it.subscriptionLicenses }.count { allPurchases.status(it, nowDate) == LicenseStatus.Churned }
                }.toBigInteger(),
                columnTransferredLicenses to displayedCustomers.sumOf { transferredLicenses(it) }.toBigInteger(),
                columnSales to when (customerFilter) {
                    null -> totalSales.getTotalAmount()
                    else -> MonetaryAmountTracker(exchangeRates).also { sum -> displayedCustomers.forEach { sum.add(it.totalSales) } }.getTotalAmount()
                },
            )
        )

        return listOf(SimpleTableSection(rows, footer = footer))
    }
}
