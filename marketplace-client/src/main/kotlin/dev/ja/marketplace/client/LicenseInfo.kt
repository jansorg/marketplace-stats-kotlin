/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.currency.WithAmounts
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItem
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
import javax.money.MonetaryAmount

/**
 * Purchase of a single plugin license, identified by a unique ID.
 */
data class LicenseInfo(
    // unique identifier of the license, there may be multiple LicenseInfo items with the same license ID
    val id: LicenseId,
    // amount of this particular license
    override val amount: MonetaryAmount,
    // same as amount, but converted from "currency" to USD
    override val amountUSD: MonetaryAmount,
    // the sale of this particular license purchase, which also contains the saleLineItem
    val sale: PluginSale,
    // the sale line item of this particular license purchase
    val saleLineItem: PluginSaleItem,
) : WithAmounts, Comparable<LicenseInfo> {
    init {
        require(amountUSD.currency.currencyCode == "USD")
    }

    /** Dates, when this license is valid */
    val validity: YearMonthDayRange?
        get() {
            return (saleLineItem as? SubscriptionPluginSaleItem)?.subscriptionDates
        }

    val isNewLicense: Boolean
        get() {
            return saleLineItem.type == PluginSaleItemType.New
        }

    val isRenewalLicense: Boolean
        get() {
            return saleLineItem.type == PluginSaleItemType.Renew
        }

    /** Stored, because the reports check it for each license many times. */
    val isPaidLicense: Boolean = !amountUSD.isZero && !saleLineItem.isFreeLicense

    val isSubscriptionLicense: Boolean
        get() {
            return saleLineItem is SubscriptionPluginSaleItem
        }

    override fun compareTo(other: LicenseInfo): Int {
        val validity = this.validity
        val otherValidity = other.validity
        return when {
            validity == null && otherValidity == null -> 0
            validity == null -> +1
            otherValidity == null -> -1
            else -> validity.compareTo(otherValidity)
        }
    }

    companion object {
        fun createFrom(sales: List<PluginSale>): List<LicenseInfo> {
            val expectedSize = sales.sumOf { sale -> sale.lineItems.sumOf { lineItem -> lineItem.licenseIds.size } }
            val licenses = ArrayList<LicenseInfo>(expectedSize)

            sales.forEach { sale ->
                // Special sales, e.g. with manual changes by JetBrains Sales personnel, can have a total, which differs
                // from the sum of the line items, e.g. "0.0" with line items of the regular amounts.
                val amounts = MonetaryAmountSplitter.allocate(sale.amount, sale.lineItems.map { it.amount })
                val amountsUSD = MonetaryAmountSplitter.allocate(sale.amountUSD, sale.lineItems.map { it.amountUSD })
                sale.lineItems.forEachIndexed { index, lineItem ->
                    MonetaryAmountSplitter.split(amounts[index], amountsUSD[index], lineItem.licenseIds) { amount, amountUSD, license ->
                        licenses += LicenseInfo(license, amount, amountUSD, sale, lineItem)
                    }
                }
            }
            licenses.sort()

            return licenses
        }
    }
}