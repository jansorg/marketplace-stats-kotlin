/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.YearMonthDayRange
import javax.money.MonetaryAmount

data class PluginSaleItem(
    val type: PluginSaleItemType,
    val licenseIds: List<LicenseId>,
    val subscriptionDates: YearMonthDayRange,
    val amount: MonetaryAmount,
    val amountUSD: MonetaryAmount,
    val discountDescriptions: List<PluginSaleItemDiscount>
) {
    val isFreeLicense: Boolean = discountDescriptions.any { it.percent == 100.0 }
}