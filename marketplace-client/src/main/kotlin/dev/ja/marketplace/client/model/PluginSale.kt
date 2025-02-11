/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.PluginSaleSerializer
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.currency.WithAmounts
import kotlinx.serialization.Serializable
import javax.money.MonetaryAmount

@Serializable(PluginSaleSerializer::class)
data class PluginSale(
    val ref: String,
    val date: YearMonthDay,
    override val amount: MonetaryAmount,
    override val amountUSD: MonetaryAmount,
    val licensePeriod: LicensePeriod? = null,
    val customer: CustomerInfo,
    val reseller: ResellerInfo? = null,
    val lineItems: List<PluginSaleItem>
) : Comparable<PluginSale>, WithAmounts {
    override fun compareTo(other: PluginSale): Int {
        return date.compareTo(other.date)
    }
}