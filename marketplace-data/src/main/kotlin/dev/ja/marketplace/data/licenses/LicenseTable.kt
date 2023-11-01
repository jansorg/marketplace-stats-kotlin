/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.marketplace.client.Currency
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.withCurrency
import dev.ja.marketplace.data.*

class LicenseTable(
    private val showDetails: Boolean = true,
    private val showFooter: Boolean = false,
    private val licenseFilter: (LicenseInfo) -> Boolean = { true },
) : SimpleDataTable("Licenses", "licenses", "section-wide"), MarketplaceDataSink {
    private val columnLicenseId = DataTableColumn("license-id", "License ID", "col-right")
    private val columnPurchaseDate = DataTableColumn("sale-date", "Purchase", "date")
    private val columnValidityStart = DataTableColumn("license-validity", "License Start", "date")
    private val columnValidityEnd = DataTableColumn("license-validity", "End", "date")
    private val columnCustomerName = DataTableColumn("customer", "Name", cssStyle = "max-width: 35%")
    private val columnCustomerId = DataTableColumn("customer-id", "Cust. ID", "num")
    private val columnAmountUSD = DataTableColumn("sale-amount-usd", "Amount", "num")
    private val columnDiscount = DataTableColumn("license-discount", "Discount", "num")
    private val columnLicenseType = DataTableColumn("license-type", "Period")
    private val columnLicenseRenewalType = DataTableColumn("license-type", "Type")

    private val data = mutableListOf<LicenseInfo>()

    private var pluginId: PluginId? = null

    override val columns: List<DataTableColumn> = listOfNotNull(
        columnPurchaseDate,
        columnValidityStart,
        columnValidityEnd,
        columnCustomerName.takeIf { showDetails },
        columnCustomerId.takeIf { showDetails },
        columnAmountUSD,
        columnDiscount,
        columnLicenseType.takeIf { showDetails },
        columnLicenseRenewalType,
        columnLicenseId,
    )

    override fun init(data: PluginData) {
        this.pluginId = data.pluginId
    }

    override fun process(licenseInfo: LicenseInfo) {
        if (licenseFilter(licenseInfo)) {
            data += licenseInfo
        }
    }

    override val sections: List<DataTableSection>
        get() {
            val now = YearMonthDay.now()

            // first by date, then days by new/renew, then same values by annual/monthly, then by amount
            val comparator = Comparator.comparing<LicenseInfo?, YearMonthDay?> { it.sale.date }.reversed()
                .thenDescending(Comparator.comparing { it.validity.start })
                .then(Comparator.comparing { it.saleLineItem.type })
                .thenDescending(Comparator.comparing { it.sale.licensePeriod })
                .thenDescending(Comparator.comparing { it.amountUSD.sortValue() })

            val licenseMaxValidity = data.groupBy { it.id }.mapValues { it.value.maxOf { l -> l.validity.end } }

            var lastPurchaseDate: YearMonthDay? = null
            val rows = data
                .sortedWith(comparator)
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
                            columnCustomerName to (license.sale.customer.name ?: "—"),
                            columnCustomerId to LinkedCustomer(license.sale.customer.code, pluginId = pluginId!!),
                            columnAmountUSD to license.amountUSD.withCurrency(Currency.USD),
                            columnLicenseType to license.sale.licensePeriod,
                            columnLicenseRenewalType to license.saleLineItem.type,
                            columnDiscount to license.saleLineItem.discountDescriptions
                                .mapNotNull { it.percent }
                                .sorted()
                                .map { it.asPercentageValue(false) }
                        ),
                        cssClass = if (licenseMaxValidity[license.id]!! < now) "disabled" else null,
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

            val licenseCount = licenseMaxValidity.size
            val activeLicenseCount = licenseMaxValidity.count { it.value >= now }
            val footer = when {
                showFooter -> SimpleRowGroup(
                    SimpleDateTableRow(
                        values = mapOf(
                            columnAmountUSD to data.sumOf(LicenseInfo::amountUSD).withCurrency(Currency.USD),
                            columnLicenseId to (if (licenseCount == 0) "—" else listOf(
                                "$activeLicenseCount active",
                                "$licenseCount total"
                            ))
                        ),
                    )
                )

                else -> null
            }

            return listOf(SimpleTableSection(rows, null, footer = footer))
        }
}
