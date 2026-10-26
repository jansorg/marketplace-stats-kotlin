/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.lineItems

import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.PluginSaleItem
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.licenses.LicenseStatus
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import javax.money.MonetaryAmount

/**
 * The licenses of a line item of a sale, with the end of the latest purchase of each license of all customers,
 * e.g. to see if a license was renewed later. The footer contains the total amounts of the line item.
 */
class LineItemLicensesTable(
    private val lineItem: PluginSaleItem,
    private val nowDate: YearMonthDay = YearMonthDay.now(),
) : SimpleDataTable("Licenses", "line-item-licenses", "table-striped"), MarketplaceDataSink {
    private val columnLicenseId = DataTableColumn("license-id", "License ID", "col-right")
    private val columnAmount = DataTableColumn("license-amount", "Amount", "num")
    private val columnAmountUSD = DataTableColumn("license-amount-usd", "Amount USD", "num")
    private val columnPaidAmount = DataTableColumn("license-paid-amount", "Paid Amount", "num", tooltip = "Amount after the share of the Marketplace")
    private val columnLicensedFrom = DataTableColumn(
        "license-from", "Licensed From", "date", tooltip = "Start of the first purchase of the license, of all customers"
    )
    private val columnLicensedUntil = DataTableColumn(
        "license-until", "Licensed Until", "date", tooltip = "End of the latest purchase of the license, of all customers"
    )
    private val columnRenewedBy = DataTableColumn(
        "license-renewed-by", "Renewed By", tooltip = "The customer, who renewed the license, e.g. after a transfer to an organization"
    )

    private val licenses = mutableListOf<LicenseInfo>()
    // the purchase with the latest end of each license, of all customers
    private val latestPurchases = Object2ObjectOpenHashMap<LicenseId, LicenseInfo>()
    // the start of the first purchase of each license, of all customers
    private val firstStarts = Object2ObjectOpenHashMap<LicenseId, YearMonthDay>()
    private var pluginId: PluginId? = null

    // the amounts in USD would repeat the amounts of sales in USD, the column of other customers is only shown if needed
    override val columns: List<DataTableColumn>
        get() = listOfNotNull(
            columnLicenseId,
            columnAmount,
            columnAmountUSD.takeUnless { licenses.all { it.sale.amount.currency == MarketplaceCurrencies.USD } },
            columnPaidAmount,
            columnLicensedFrom,
            columnLicensedUntil,
            columnRenewedBy.takeIf { licenses.any { renewedBy(it) != null } },
        )

    override suspend fun init(data: PluginData) {
        super.init(data)
        this.pluginId = data.pluginId
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        if (licenseInfo.saleLineItem === lineItem) {
            licenses += licenseInfo
        }

        val validity = licenseInfo.validity ?: return
        firstStarts[licenseInfo.id] = firstStarts[licenseInfo.id]?.let { minOf(it, validity.start) } ?: validity.start
        val end = validity.end
        val latest = latestPurchases[licenseInfo.id]
        if (latest == null || end >= latest.validity!!.end) {
            latestPurchases[licenseInfo.id] = licenseInfo
        }
    }

    /** The customer of the latest purchase of the license, if it's another customer. */
    private fun renewedBy(license: LicenseInfo) = latestPurchases[license.id]?.sale?.customer
        ?.takeIf { it.code != license.sale.customer.code }

    override suspend fun createSections(): List<DataTableSection> {
        val rows = licenses.map { license ->
            val latestEnd = latestPurchases[license.id]?.validity?.end
            val isChurned = latestEnd != null && LicenseStatus.of(latestEnd, nowDate) == LicenseStatus.Churned
            val renewedBy = renewedBy(license)
            SimpleDateTableRow(
                values = mapOf(
                    columnLicenseId to LinkedLicense(license.id, pluginId!!),
                    columnAmount to license.amount,
                    columnAmountUSD to license.amountUSD,
                    columnPaidAmount to Marketplace.paidAmount(license.sale.date, license.amount),
                    columnLicensedFrom to (firstStarts[license.id] ?: NoValue),
                    columnLicensedUntil to when {
                        latestEnd == null -> NoValue
                        isChurned -> ExpiredLicenseEnd(latestEnd, latestEnd)
                        else -> latestEnd
                    },
                    columnRenewedBy to renewedBy?.let { customer ->
                        customer.name?.let { LinkedCustomerName(it, customer.code, pluginId!!) } ?: LinkedCustomer(customer.code, pluginId!!)
                    },
                ),
                cssClass = if (isChurned) "disabled" else null,
            )
        }
        val footer = licenses.takeIf { it.isNotEmpty() }?.let { lineItemLicenses ->
            val amount = lineItemLicenses.map(LicenseInfo::amount).reduce(MonetaryAmount::add)
            SimpleRowGroup(
                SimpleDateTableRow(
                    columnAmount to amount,
                    columnAmountUSD to lineItemLicenses.map(LicenseInfo::amountUSD).reduce(MonetaryAmount::add),
                    columnPaidAmount to Marketplace.paidAmount(lineItemLicenses.first().sale.date, amount),
                )
            )
        }
        return listOf(SimpleTableSection(rows, footer = footer))
    }
}
