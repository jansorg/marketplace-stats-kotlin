/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.PluginSaleItemDiscount
import dev.ja.marketplace.client.WithAmounts
import java.math.BigDecimal

/**
 *
 */
class SaleCalculator {
    fun nextSale(licenseInfo: LicenseInfo): WithAmounts {
        if (licenseInfo.saleLineItem.discountDescriptions.any { it.isFreeLicenseDiscount() }) {
            return Amounts.zero(licenseInfo.currency)
        }


        val resellerDiscount = licenseInfo.saleLineItem.discountDescriptions
            .filter { it.isResellerDiscount() }
            .sumOf { it.percent ?: 0.0 }
        val continuityDiscount = licenseInfo.saleLineItem.discountDescriptions
            .firstOrNull { it.isContinuityDiscount() }
            ?.percent
        val nextContinuityDiscount = when (continuityDiscount) {
            null -> 20.0
            20.0 -> 40.0
            else -> 40.0
        }

        val discountFactor = BigDecimal(1.0 * (1.0 - resellerDiscount / 100.0) * (1.0 - nextContinuityDiscount / 100.0))
        return Amounts(
            licenseInfo.amount * discountFactor,
            licenseInfo.currency,
            licenseInfo.amountUSD * discountFactor
        )
    }

    companion object {
        private fun PluginSaleItemDiscount.isFreeLicenseDiscount(): Boolean {
            return this.percent == 100.0
        }

        private fun PluginSaleItemDiscount.isResellerDiscount(): Boolean {
            return this.description.contains("Reseller discount for 3rd-party plugins")
        }

        private fun PluginSaleItemDiscount.isContinuityDiscount(): Boolean {
            return this.description.contains("continuity discount")
        }
    }
}