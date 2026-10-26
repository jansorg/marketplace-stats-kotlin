/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.lineItems

import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItem
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
import dev.ja.marketplace.data.format.Formatters

/**
 * The details of a line item of a sale, which the page of a sale shows as text.
 */
class SaleLineItemSummary(val sale: PluginSale, val lineItem: PluginSaleItem) {
    val typeLabel: String
        get() = when (lineItem.type) {
            PluginSaleItemType.New -> "New"
            PluginSaleItemType.Renew -> "Renewal"
        }

    /** `null` for perpetual licenses. */
    val subscriptionDates: YearMonthDayRange?
        get() = (lineItem as? SubscriptionPluginSaleItem)?.subscriptionDates

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
