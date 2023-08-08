/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.marketplace.client.Currency
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.withCurrency
import dev.ja.marketplace.data.*

class LicenseTable : SimpleDataTable("Licenses", "licenses", "section-wide"), MarketplaceDataSink {
    private val columnLicenseId = DataTableColumn("license-id", "License ID")
    private val columnPurchaseDate = DataTableColumn("sale-date", "Purchase", "date")
    private val columnValidityStart = DataTableColumn("license-validity", "License Start", "date")
    private val columnValidityEnd = DataTableColumn("license-validity", "End", "date")
    private val columnCustomerName = DataTableColumn("customer", "Name", cssStyle = "max-width: 35%")
    private val columnCustomerId = DataTableColumn("customer-id", "Cust. ID", "num")
    private val columnAmountUSD = DataTableColumn("sale-amount-usd", "Amount", "num")
    private val columnDiscount = DataTableColumn("license-discount", "Discount", "num")
    private val columnLicenseType = DataTableColumn("license-type", "License")
    private val columnLicenseRenewalType = DataTableColumn("license-type", "Type")

    private val data = mutableListOf<LicenseInfo>()

    override val columns: List<DataTableColumn> = listOf(
        columnPurchaseDate,
        columnValidityStart,
        columnValidityEnd,
        columnCustomerName,
        columnCustomerId,
        columnAmountUSD,
        columnDiscount,
        columnLicenseType,
        columnLicenseRenewalType,
        columnLicenseId,
    )

    override val sections: List<DataTableSection>
        get() {
            var lastPurchaseDate: YearMonthDay? = null
            val rows = data
                .sortedByDescending { it.sale.amountUSD.sortValue() }
                .sortedByDescending { it.sale.date }
                .map { license ->
                    val purchaseDate = license.sale.date
                    val showPurchaseDate = lastPurchaseDate != purchaseDate
                    lastPurchaseDate = purchaseDate

                    SimpleDateTableRow(
                        values = mapOf(
                            columnLicenseId to license.id,
                            columnPurchaseDate to if (showPurchaseDate) purchaseDate else null,
                            columnValidityStart to license.validity.start,
                            columnValidityEnd to license.validity.end,
                            columnCustomerName to license.sale.customer.name,
                            columnCustomerId to license.sale.customer.code,
                            columnAmountUSD to license.amountUSD.withCurrency(Currency.USD),
                            columnLicenseType to license.sale.licensePeriod,
                            columnLicenseRenewalType to license.saleLineItem.type,
                            columnDiscount to license.saleLineItem.discountDescriptions
                                .mapNotNull { it.percent }
                                .sorted()
                                .map { it.asPercentageValue(false) }
                        ),
                        tooltips = mapOf(
                            columnDiscount to license.saleLineItem.discountDescriptions
                                .sortedBy { it.percent ?: 0.0 }
                                .joinToString("\n") {
                                    when {
                                        it.percent != null -> "%.2f%% (%s)".format(it.percent, it.description)
                                        else -> it.description
                                    }
                                }
                        )
                    )
                }
            return listOf(SimpleTableSection(rows, null))
        }

    override fun process(licenseInfo: LicenseInfo) {
        data += licenseInfo
    }
}
