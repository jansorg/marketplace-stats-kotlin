/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.PaymentAmountTracker
import dev.ja.marketplace.util.sortValue
import dev.ja.marketplace.util.takeNullable

class LicenseTable(
    private val maxTableRows: Int? = null,
    private val showDetails: Boolean = true,
    private val showFooter: Boolean = false,
    private val showLicenseColumn: Boolean = true,
    private val showPurchaseColumn: Boolean = true,
    private val nowDate: YearMonthDay = YearMonthDay.now(),
    private val supportedChurnStyling: Boolean = true,
    private val showOnlyLatestLicenseInfo: Boolean = false,
    private val showReseller: Boolean = false,
    private val licenseFilter: (LicenseInfo) -> Boolean = { true },
) : SimpleDataTable("Licenses", "licenses", "table-column-wide"), MarketplaceDataSink {
    private val columnLicenseId = DataTableColumn("license-id", "License ID", "col-right")
    private val columnRefNum = DataTableColumn("license-refnum", "Ref Num", "col-right")
    private val columnPurchaseDate = DataTableColumn("sale-date", "Purchase", "date")
    private val columnValidityStart = DataTableColumn("license-validity", "License Start", "date")
    private val columnValidityEnd = DataTableColumn("license-validity", "End", "date")
    private val columnCustomerName = DataTableColumn("customer", "Name", cssStyle = "width: 20%; max-width: 35%")
    private val columnCustomerId = DataTableColumn("customer-id", "Cust. ID", "num")
    private val columnAmount = DataTableColumn("sale-amount", "Amount", "num")
    private val columnLocalAmount = DataTableColumn("sale-amount-local", null, "num")
    private val columnDiscount = DataTableColumn("license-discount", "Discount", "num")
    private val columnLicenseType = DataTableColumn("license-type", "Period")
    private val columnLicenseRenewalType = DataTableColumn("license-type", "Type")
    private val columnReseller = DataTableColumn("reseller", "Reseller")

    private val data = mutableListOf<LicenseInfo>()
    private val licenseMaxValidity = mutableMapOf<LicenseId, YearMonthDay>()
    private val filteredLicenseMaxValidity = mutableMapOf<LicenseId, YearMonthDay>()
    private var pluginId: PluginId? = null

    override val isLimitedRendering: Boolean
        get() {
            return this.maxTableRows != null
        }

    override val columns: List<DataTableColumn> = listOfNotNull(
        columnPurchaseDate.takeIf { showPurchaseColumn },
        columnValidityStart,
        columnValidityEnd,
        columnCustomerName.takeIf { showDetails },
        columnAmount,
        columnLocalAmount.takeIf { showDetails },
        columnLicenseRenewalType,
        columnLicenseType.takeIf { showDetails },
        columnDiscount,
        columnReseller.takeIf { showReseller },
        columnCustomerId.takeIf { showDetails },
        columnLicenseId.takeIf { showLicenseColumn },
        columnRefNum,
    )

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.pluginId = data.pluginId
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        if (!licenseInfo.isSubscriptionLicense) {
            return
        }

        val licenseId = licenseInfo.id
        licenseMaxValidity[licenseId] = maxOfNullable(licenseMaxValidity[licenseId], licenseInfo.validity!!.end)

        if (licenseFilter(licenseInfo)) {
            data += licenseInfo
            filteredLicenseMaxValidity[licenseId] = maxOfNullable(filteredLicenseMaxValidity[licenseId], licenseInfo.validity!!.end)
        }
    }

    // first by date, then days by new/renew, then same values by annual/monthly, then by amount
    private val comparator = Comparator.comparing<LicenseInfo?, YearMonthDay?> { it.sale.date }.reversed()
        .thenDescending(Comparator.comparing { it.validity!!.start })
        .then(Comparator.comparing { it.saleLineItem.type })
        .thenDescending(Comparator.comparing { it.sale.licensePeriod })
        .thenDescending(Comparator.comparing { it.amountUSD.sortValue() })

    override suspend fun createSections(): List<DataTableSection> {
        var previousPurchaseDate: YearMonthDay? = null
        val shownLicenseInfos = if (showOnlyLatestLicenseInfo) mutableSetOf<LicenseId>() else null
        // any date because the consumed data is already filtered
        val amountTracker = PaymentAmountTracker(YearMonthDayRange.MAX, exchangeRates)
        val rows = data
            .sortedWith(comparator)
            .takeNullable(maxTableRows)
            .mapNotNull { license ->
                // only display the latest renewal (or the first purchase if new)
                if (shownLicenseInfos != null) {
                    try {
                        if (license.id in shownLicenseInfos) {
                            return@mapNotNull null
                        }
                    } finally {
                        shownLicenseInfos += license.id
                    }
                }

                val purchaseDate = license.sale.date
                val showPurchaseDate = previousPurchaseDate != purchaseDate
                previousPurchaseDate = purchaseDate

                amountTracker.add(license.sale.date, license.amountUSD, license.amount)

                SimpleDateTableRow(
                    values = mapOf(
                        columnLicenseId to LinkedLicense(license.id, pluginId!!),
                        columnRefNum to LinkedRefNum(license.sale.ref, pluginId!!),
                        columnPurchaseDate to if (showPurchaseDate) purchaseDate else null,
                        columnValidityStart to license.validity!!.start,
                        columnValidityEnd to license.validity!!.end,
                        columnCustomerName to (license.sale.customer.name ?: NoValue),
                        columnCustomerId to LinkedCustomer(license.sale.customer.code, pluginId = pluginId!!),
                        columnLocalAmount to license.amount,
                        columnAmount to license.renderAmount(license.sale.date),
                        columnLicenseType to license.sale.licensePeriod,
                        columnLicenseRenewalType to license.saleLineItem.type,
                        columnDiscount to license.saleLineItem.discountDescriptions
                            .mapNotNull { it.percent }
                            .sorted()
                            .map { it.asPercentageValue(false) },
                        columnReseller to license.sale.reseller?.name,
                    ),
                    cssClass = if (supportedChurnStyling && licenseMaxValidity[license.id]!! < nowDate) "disabled" else null,
                    tooltips = mapOf(
                        columnDiscount to license.saleLineItem.discountDescriptions
                            .sortedBy { it.percent ?: 0.0 }
                            .joinToString("\n") {
                                when {
                                    it.percent != null -> "%.2f%% (%s)".format(it.percent, it.description)
                                    else -> it.description
                                }
                            },
                        columnReseller to license.sale.reseller?.tooltip,
                    )
                )
            }

        val licenseCount = filteredLicenseMaxValidity.size
        val activeLicenseCount = filteredLicenseMaxValidity.count { it.value >= nowDate }
        val footer = when {
            showFooter -> SimpleRowGroup(
                SimpleDateTableRow(
                    values = mapOf(
                        columnAmount to amountTracker.totalAmount,
                        columnLicenseId to (if (licenseCount == 0) NoValue else listOfNotNull(
                            "$activeLicenseCount active".takeIf { supportedChurnStyling },
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

private fun <T : Comparable<T>> maxOfNullable(a: T?, b: T?): T {
    return when {
        a == null -> b!!
        b == null -> a
        else -> maxOf(a, b)
    }
}
