/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.PluginReleaseDate
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.PaymentAmountTracker
import dev.ja.marketplace.util.sortValue
import dev.ja.marketplace.util.takeNullable
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import javax.money.MonetaryAmount

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
    /** Only licenses with this status, all licenses if it's `null`. */
    private val statusFilter: LicenseStatus? = null,
    /** The release dates for the fallback licenses, the table shows the fallback versions if they're available. */
    private val fallbackReleases: List<PluginReleaseDate>? = null,
    private val licenseFilter: (LicenseInfo) -> Boolean = { true },
) : SimpleDataTable("Licenses", "licenses", "table-column-wide"), MarketplaceDataSink {
    private val columnLicenseId = DataTableColumn("license-id", "License ID", "col-right")
    private val columnRefNum = DataTableColumn("license-refnum", "Ref Num", "col-right")
    private val columnPurchaseDate = DataTableColumn("sale-date", "Purchase", "date")
    private val columnValidityStart = DataTableColumn("license-validity", "License Start", "date")
    private val columnValidityEnd = DataTableColumn("license-validity-end", "End", "date")
    private val columnLicensedFrom = DataTableColumn(
        "license-from", "Licensed From", "date", tooltip = "Start of the first purchase of the license, of all customers"
    )
    private val columnCustomerName = DataTableColumn("customer", "Name", cssStyle = "width: 20%; max-width: 35%")
    private val columnCustomerId = DataTableColumn("customer-id", "Cust. ID", "num")
    private val columnAmount = DataTableColumn("sale-amount", "Amount", "num")
    private val columnLocalAmount = DataTableColumn("sale-amount-local", null, "num")
    private val columnDiscount = DataTableColumn("license-discount", "Discount", "num")
    private val columnLicenseType = DataTableColumn("license-type", "Period")
    private val columnLicenseRenewalType = DataTableColumn("license-type", "Type")
    private val columnReseller = DataTableColumn("reseller", "Reseller")
    private val columnFallbackVersion = DataTableColumn(
        "license-fallback-version", "Fallback Version", "num",
        tooltip = "The version, which the perpetual fallback license covers, if the license isn't renewed"
    )

    private val data = mutableListOf<LicenseInfo>()
    private val licenseMaxValidity = Object2ObjectOpenHashMap<LicenseId, YearMonthDay>()
    private val licenseMinValidity = Object2ObjectOpenHashMap<LicenseId, YearMonthDay>()
    // the purchase with the latest end of each license, of all customers
    private val licenseLatestPurchase = Object2ObjectOpenHashMap<LicenseId, LicenseInfo>()
    private val filteredLicenseMaxValidity = Object2ObjectOpenHashMap<LicenseId, YearMonthDay>()
    // all purchases of the licenses, a fallback license depends on all of them, e.g. of the purchases of other customers
    private val allSubscriptionLicenses = mutableListOf<LicenseInfo>()
    private var pluginId: PluginId? = null

    override val isLimitedRendering: Boolean
        get() {
            return this.maxTableRows != null
        }

    // with a row per license, the row only shows the latest purchase, the first purchase is shown by "Licensed From"
    override val columns: List<DataTableColumn> = listOfNotNull(
        columnPurchaseDate.takeIf { showPurchaseColumn },
        columnLicensedFrom.takeIf { showOnlyLatestLicenseInfo },
        columnValidityStart,
        columnValidityEnd,
        columnFallbackVersion.takeIf { fallbackReleases != null },
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
        licenseMinValidity[licenseId] = licenseMinValidity[licenseId]?.let { minOf(it, licenseInfo.validity!!.start) } ?: licenseInfo.validity!!.start
        if (licenseInfo.validity!!.end >= (licenseLatestPurchase[licenseId]?.validity?.end ?: YearMonthDay.MIN)) {
            licenseLatestPurchase[licenseId] = licenseInfo
        }
        if (fallbackReleases != null) {
            allSubscriptionLicenses += licenseInfo
        }

        if (licenseFilter(licenseInfo)) {
            data += licenseInfo
            filteredLicenseMaxValidity[licenseId] = maxOfNullable(filteredLicenseMaxValidity[licenseId], licenseInfo.validity!!.end)
        }
    }

    // first by date, then days by new/renew, then same values by annual/monthly, then by amount
    private val comparator = Comparator.comparing<LicenseInfo, YearMonthDay> { it.sale.date }.reversed()
        .thenDescending(Comparator.comparing { it.validity!!.start })
        .then(Comparator.comparing { it.saleLineItem.type })
        .thenDescending(Comparator.comparing { it.sale.licensePeriod })
        .thenDescending(Comparator.comparing { it.amountUSD.sortValue() })

    override suspend fun createSections(): List<DataTableSection> {
        var previousPurchaseDate: YearMonthDay? = null
        // any date because the consumed data is already filtered
        val amountTracker = PaymentAmountTracker(YearMonthDayRange.MAX, exchangeRates)
        val fallbackVersions = fallbackVersions()
        val rows = data
            .filter { statusFilter == null || status(it.id) == statusFilter }
            .sortedWith(comparator)
            // the purchases of a license in the same sale are a single row with the period of all of them,
            // e.g. a purchase until a date and a purchase, which starts on the next day
            .groupBy { it.id to it.sale.ref }
            .values
            .toList()
            // only display the latest renewal (or the first purchase if new), before the rows are limited
            .let { sales -> if (showOnlyLatestLicenseInfo) sales.distinctBy { it.first().id } else sales }
            .takeNullable(maxTableRows)
            .map { purchases ->
                val license = purchases.first()
                val purchaseDate = license.sale.date
                val showPurchaseDate = previousPurchaseDate != purchaseDate
                previousPurchaseDate = purchaseDate

                for (purchase in purchases) {
                    amountTracker.add(purchase.sale.date, purchase.amountUSD, purchase.amount)
                }

                val validity = YearMonthDayRange(purchases.minOf { it.validity!!.start }, purchases.maxOf { it.validity!!.end })
                val discounts = purchases.flatMap { it.saleLineItem.discountDescriptions }.distinct()
                val validityEnd = validity.end
                val licenseEnd = licenseMaxValidity[license.id]!!
                val isExpired = supportedChurnStyling && licenseEnd < nowDate
                // the latest purchase of the customer, if another customer renewed the license
                val transferredTo = licenseLatestPurchase.getValue(license.id).sale.customer.takeIf {
                    it.code != license.sale.customer.code && validityEnd == filteredLicenseMaxValidity[license.id]
                }

                SimpleDateTableRow(
                    values = mapOf(
                        columnLicenseId to LinkedLicense(license.id, pluginId!!),
                        columnRefNum to LinkedRefNum(license.sale.ref, pluginId!!),
                        columnPurchaseDate to if (showPurchaseDate) purchaseDate else null,
                        columnLicensedFrom to licenseMinValidity[license.id],
                        columnValidityStart to validity.start,
                        columnValidityEnd to when {
                            isExpired -> ExpiredLicenseEnd(validityEnd, licenseEnd)
                            transferredTo != null -> TransferredLicenseEnd(
                                validityEnd, LinkedCustomer(transferredTo.code, pluginId!!), transferredTo.name, licenseEnd
                            )

                            else -> validityEnd
                        },
                        // the version of the license, all purchases of a license show it
                        columnFallbackVersion to fallbackVersions[license.id],
                        columnCustomerName to (license.sale.customer.name ?: NoValue),
                        columnCustomerId to LinkedCustomer(license.sale.customer.code, pluginId = pluginId!!),
                        columnLocalAmount to purchases.map { it.amount }.reduce(MonetaryAmount::add),
                        columnAmount to purchases.map { it.renderAmount(it.sale.date) }.reduce(MonetaryAmount::add),
                        columnLicenseType to license.sale.licensePeriod,
                        columnLicenseRenewalType to license.saleLineItem.type,
                        columnDiscount to discounts
                            .mapNotNull { it.percent }
                            .sorted()
                            .map { it.asPercentageValue(false) },
                        columnReseller to license.sale.reseller?.name,
                    ),
                    // all licenses of the page of churned licenses would be gray, they keep the symbol of expired licenses
                    cssClass = if (isExpired && statusFilter != LicenseStatus.Churned) "disabled" else null,
                    tooltips = mapOf(
                        columnDiscount to discounts
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

        val shownLicenseIds = filteredLicenseMaxValidity.keys.filter { statusFilter == null || status(it) == statusFilter }
        val licenseCount = shownLicenseIds.size
        val activeLicenseCount = shownLicenseIds.count { status(it) == LicenseStatus.Active }
        val footer = when {
            showFooter -> SimpleRowGroup(
                SimpleDateTableRow(
                    values = mapOf(
                        columnAmount to amountTracker.totalAmount,
                        columnLicenseId to (if (licenseCount == 0) NoValue else listOfNotNull(
                            "$activeLicenseCount active".takeIf { supportedChurnStyling && statusFilter != LicenseStatus.Churned },
                            "$licenseCount total"
                        ))
                    ),
                )
            )

            else -> null
        }

        return listOf(SimpleTableSection(rows, null, footer = footer))
    }

    private fun status(licenseId: LicenseId): LicenseStatus {
        return LicenseStatus.of(licenseMaxValidity.getValue(licenseId), nowDate)
    }

    /**
     * @return The fallback versions of the shown licenses.
     */
    private fun fallbackVersions(): Map<LicenseId, FallbackVersion> {
        val releases = fallbackReleases ?: return emptyMap()
        return allSubscriptionLicenses
            .filter { it.id in filteredLicenseMaxValidity }
            .groupBy { it.id }
            .mapNotNull { (licenseId, licenses) -> FallbackVersion.of(licenseId, licenses, releases) }
            .associateBy { it.licenseId }
    }
}

private fun <T : Comparable<T>> maxOfNullable(a: T?, b: T?): T {
    return when {
        a == null -> b!!
        b == null -> a
        else -> maxOf(a, b)
    }
}
