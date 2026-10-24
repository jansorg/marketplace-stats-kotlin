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
    private val columnCustomerName = DataTableColumn("customer", "Name", cssStyle = "width: 20%; max-width: 35%")
    private val columnCustomerId = DataTableColumn("customer-id", "Cust. ID", "num")
    private val columnAmount = DataTableColumn("sale-amount", "Amount", "num")
    private val columnLocalAmount = DataTableColumn("sale-amount-local", null, "num")
    private val columnDiscount = DataTableColumn("license-discount", "Discount", "num")
    private val columnLicenseType = DataTableColumn("license-type", "Period")
    private val columnLicenseRenewalType = DataTableColumn("license-type", "Type")
    private val columnReseller = DataTableColumn("reseller", "Reseller")
    private val columnFallbackVersion = DataTableColumn(
        "license-fallback-version", "Fallback Version",
        tooltip = "The version, which the perpetual fallback license covers, if the license isn't renewed. " +
                "It's shown for the latest purchase of each license."
    )

    private val data = mutableListOf<LicenseInfo>()
    private val licenseMaxValidity = Object2ObjectOpenHashMap<LicenseId, YearMonthDay>()
    // the purchase with the latest end of each license, of all customers
    private val licenseLatestPurchase = Object2ObjectOpenHashMap<LicenseId, LicenseInfo>()
    // the purchases of each license, the latest purchase is shown with the period of all purchases of its sale
    private val licensePurchases = Object2ObjectOpenHashMap<LicenseId, MutableList<LicenseInfo>>()
    private val filteredLicenseMaxValidity = Object2ObjectOpenHashMap<LicenseId, YearMonthDay>()
    // all purchases of the licenses, a fallback license depends on all of them, e.g. of the purchases of other customers
    private val allSubscriptionLicenses = mutableListOf<LicenseInfo>()
    private var pluginId: PluginId? = null

    override val isLimitedRendering: Boolean
        get() {
            return this.maxTableRows != null
        }

    override val columns: List<DataTableColumn> = listOfNotNull(
        columnPurchaseDate.takeIf { showPurchaseColumn },
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
        if (licenseInfo.validity!!.end >= (licenseLatestPurchase[licenseId]?.validity?.end ?: YearMonthDay.MIN)) {
            licenseLatestPurchase[licenseId] = licenseInfo
        }
        if (fallbackReleases != null) {
            allSubscriptionLicenses += licenseInfo
        }
        if (showOnlyLatestLicenseInfo) {
            licensePurchases.getOrPut(licenseId) { mutableListOf() } += licenseInfo
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
            // only display the latest renewal (or the first purchase if new), before the rows are limited
            .let { sorted -> if (showOnlyLatestLicenseInfo) sorted.distinctBy { it.id } else sorted }
            .takeNullable(maxTableRows)
            .map { license ->
                val purchaseDate = license.sale.date
                val showPurchaseDate = previousPurchaseDate != purchaseDate
                previousPurchaseDate = purchaseDate

                amountTracker.add(license.sale.date, license.amountUSD, license.amount)

                // the latest purchase of a license shows the period of all purchases of its sale,
                // e.g. of a sale with a purchase until a date and a purchase, which starts on the next day
                val validity = when {
                    showOnlyLatestLicenseInfo -> licensePurchases.getValue(license.id)
                        .filter { it.sale.ref == license.sale.ref }
                        .map { it.validity!! }
                        .let { YearMonthDayRange(it.minOf { range -> range.start }, it.maxOf { range -> range.end }) }

                    else -> license.validity!!
                }
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
                        columnValidityStart to validity.start,
                        columnValidityEnd to when {
                            isExpired -> ExpiredLicenseEnd(validityEnd, licenseEnd)
                            transferredTo != null -> TransferredLicenseEnd(
                                validityEnd, LinkedCustomer(transferredTo.code, pluginId!!), transferredTo.name, licenseEnd
                            )

                            else -> validityEnd
                        },
                        columnFallbackVersion to fallbackVersions
                            .takeIf { validityEnd == filteredLicenseMaxValidity[license.id] }
                            ?.remove(license.id),
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
                    // all licenses of the page of churned licenses would be gray, they keep the symbol of expired licenses
                    cssClass = if (isExpired && statusFilter != LicenseStatus.Churned) "disabled" else null,
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
     * @return The fallback versions of the shown licenses. The caller removes the version of a license,
     *  when it's shown for the latest purchase of the license.
     */
    private fun fallbackVersions(): MutableMap<LicenseId, FallbackVersion> {
        val releases = fallbackReleases ?: return mutableMapOf()
        return allSubscriptionLicenses
            .filter { it.id in filteredLicenseMaxValidity }
            .groupBy { it.id }
            .mapNotNullTo(mutableListOf()) { (licenseId, licenses) -> FallbackVersion.of(licenseId, licenses, releases) }
            .associateByTo(mutableMapOf()) { it.licenseId }
    }
}

private fun <T : Comparable<T>> maxOfNullable(a: T?, b: T?): T {
    return when {
        a == null -> b!!
        b == null -> a
        else -> maxOf(a, b)
    }
}
