/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import javax.money.MonetaryAmount

/**
 * Purchase of a single plugin license, identified by a unique ID.
 */
data class LicenseInfo(
    // unique identifier of the license, there may be multiple LicenseInfo items with the same license ID
    val id: LicenseId,
    // dates, when this license is valid
    val validity: YearMonthDayRange,
    // amount of this particular license
    override val amount: MonetaryAmount,
    // same as amount, but converted from "currency" to USD
    override val amountUSD: MonetaryAmount,
    // the sale of this particular license purchase, which also contains the saleLineItem
    val sale: PluginSale,
    // the sale line item of this particular license purchase
    val saleLineItem: PluginSaleItem,
) : WithDateRange, WithAmounts, Comparable<LicenseInfo> {
    init {
        require(amountUSD.currency.currencyCode == "USD")
    }

    val isNewLicense: Boolean
        get() {
            return saleLineItem.type == PluginSaleItemType.New
        }

    val isRenewalLicense: Boolean
        get() {
            return saleLineItem.type == PluginSaleItemType.Renew
        }

    val isPaidLicense: Boolean
        get() {
            return !amountUSD.isZero && !saleLineItem.isFreeLicense
        }

    override val dateRange: YearMonthDayRange
        get() = validity

    override fun compareTo(other: LicenseInfo): Int {
        return validity.compareTo(other.validity)
    }

    companion object {
        fun createFrom(sales: List<PluginSale>): List<LicenseInfo> {
            val expectedSize = sales.sumOf { sale -> sale.lineItems.sumOf { lineItem -> lineItem.licenseIds.size } }
            val licenses = ArrayList<LicenseInfo>(expectedSize)

            sales.forEach { sale ->
                sale.lineItems.forEach { lineItem ->
                    MonetaryAmountSplitter.split(lineItem.amount, lineItem.amountUSD, lineItem.licenseIds) { amount, amountUSD, license ->
                        licenses += LicenseInfo(
                            license,
                            lineItem.subscriptionDates,
                            amount,
                            amountUSD,
                            sale,
                            lineItem
                        )
                    }
                }
            }
            licenses.sort()

            return licenses
        }
    }
}