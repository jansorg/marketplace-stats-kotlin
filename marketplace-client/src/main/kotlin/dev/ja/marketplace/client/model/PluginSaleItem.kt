/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.YearMonthDayRange
import javax.money.MonetaryAmount

interface PluginSaleItem {
    val type: PluginSaleItemType
    val licenseIds: List<LicenseId>
    val amount: MonetaryAmount
    val amountUSD: MonetaryAmount
    val discountDescriptions: List<PluginSaleItemDiscount>
    val isFreeLicense: Boolean get() = discountDescriptions.any { it.percent == 100.0 }
}

data class SubscriptionPluginSaleItem(
    override val type: PluginSaleItemType,
    override val licenseIds: List<LicenseId>,
    override val amount: MonetaryAmount,
    override val amountUSD: MonetaryAmount,
    override val discountDescriptions: List<PluginSaleItemDiscount>,
    val subscriptionDates: YearMonthDayRange
) : PluginSaleItem

data class PerpetualPluginSaleItem(
    override val type: PluginSaleItemType,
    override val licenseIds: List<LicenseId>,
    override val amount: MonetaryAmount,
    override val amountUSD: MonetaryAmount,
    override val discountDescriptions: List<PluginSaleItemDiscount>,
) : PluginSaleItem