/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.*
import java.math.BigInteger

typealias LicenseId = dev.ja.marketplace.client.LicenseId

/**
 * Purchase of a single plugin license, identified by a unique ID.
 */
data class LicenseInfo(
    // unique identifier of the license, there may be multiple LicenseInfo items with the same license ID
    val id: LicenseId,
    // dates, when this license is valid
    val validity: YearMonthDayRange,
    // amount of this particular license
    override val amount: Amount,
    // currency of Amount
    override val currency: Currency,
    // same as amount, but converted from "currency" to USD
    override val amountUSD: Amount,
    // the sale of this particular license purchase, which also contains the saleLineItem
    val sale: PluginSale,
    // the sale line item of this particular license purchase
    val saleLineItem: PluginSaleItem,
) : WithDateRange, WithAmounts, Comparable<LicenseInfo> {

    val isRenewal: Boolean
        get() {
            return saleLineItem.type == PluginSaleItemType.Renew
        }

    val isPaidLicense: Boolean
        get() {
            return amountUSD != Amount.ZERO && !saleLineItem.isFreeLicense
        }

    override val dateRange: YearMonthDayRange
        get() = validity

    override fun compareTo(other: LicenseInfo): Int {
        return validity.compareTo(other.validity)
    }

    companion object {
        fun create(sales: List<PluginSale>): List<LicenseInfo> {
            return sales.flatMap { sale ->
                val licenses = mutableListOf<LicenseInfo>()

                for (lineItem in sale.lineItems) {
                    val fixedAmount = when (sale.amount.toDouble()) {
                        0.0 -> Amount(BigInteger.ZERO)
                        else -> lineItem.amount
                    }
                    val fixedAmountUSD = when (sale.amountUSD.toDouble()) {
                        0.0 -> Amount(BigInteger.ZERO)
                        else -> lineItem.amountUSD
                    }

                    SplitAmount.split(fixedAmount, fixedAmountUSD, lineItem.licenseIds) { amount, amountUSD, license ->
                        licenses += LicenseInfo(
                            license,
                            lineItem.subscriptionDates,
                            amount,
                            sale.currency,
                            amountUSD,
                            sale,
                            lineItem
                        )
                    }
                }

                /*if (licenses.sumOf { it.amountUSD }.toDouble() != sale.amountUSD.toDouble()) {
                    println(
                        "Sum does not match: $sale. item sum: ${
                            licenses.sumOf { it.amountUSD }.toDouble()
                        }, total: ${sale.amountUSD.toDouble()}"
                    )
                }*/

                licenses.sorted()
            }
        }
    }
}
