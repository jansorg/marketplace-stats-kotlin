/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.lineItems

import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItem
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
import dev.ja.marketplace.data.format.Formatters
import javax.money.MonetaryAmount

/**
 * The details of a line item of a sale, which the page of a sale shows as text.
 */
class SaleLineItemSummary(val sale: PluginSale, val lineItem: PluginSaleItem) {
    val typeLabel: String
        get() = when (lineItem.type) {
            PluginSaleItemType.New -> "New"
            PluginSaleItemType.Renew -> "Renewal"
        }

    val licenseCount: Int
        get() = lineItem.licenseIds.size

    /** `null` for perpetual licenses. */
    val subscriptionDates: YearMonthDayRange?
        get() = (lineItem as? SubscriptionPluginSaleItem)?.subscriptionDates

    /** `true` if the sale is in USD, the amounts in USD would repeat the amounts. */
    val isUSD: Boolean
        get() = sale.amount.currency == MarketplaceCurrencies.USD

    val amount: MonetaryAmount
        get() = lineItem.amount

    val amountUSD: MonetaryAmount
        get() = lineItem.amountUSD

    /** The amount after the share of the Marketplace. */
    val paidAmount: MonetaryAmount
        get() = Marketplace.paidAmount(sale.date, lineItem.amount)

    val paidAmountUSD: MonetaryAmount
        get() = Marketplace.paidAmount(sale.date, lineItem.amountUSD)

    /** The descriptions of the discounts with their percentage, if the description doesn't contain it. */
    val discounts: List<String>
        get() = lineItem.discountDescriptions.map { discount ->
            val percent = discount.percent
            // the descriptions usually contain the percentage, e.g. "20% continuity discount"
            when {
                percent == null || "%" in discount.description -> discount.description
                else -> "${discount.description} (${Formatters.formatPercentage(percent.toBigDecimal())})"
            }
        }
}
