/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.lineItems

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItem
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
import dev.ja.marketplace.data.format.Formatters
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.PaymentAmountTracker
import dev.ja.marketplace.exchangeRate.ExchangeRates

class LineItemsTable : SimpleDataTable("Line Items", "line-items", "table-column-wide"), MarketplaceDataSink {
    private val columnRefNum = DataTableColumn("license-refnum", "Ref Num", "col-right")
    private val columnType = DataTableColumn("line-type", "Type")
    private val columnLicenseCount = DataTableColumn("license-count", "Licenses", "num")
    private val columnLicenseIds = DataTableColumn("license-ids", "License IDs")
    private val columnSubscriptionStart = DataTableColumn(
        "line-subscription-start", "Start", "date", tooltip = "Start of the subscription, empty for perpetual licenses"
    )
    private val columnSubscriptionEnd = DataTableColumn(
        "line-subscription-end", "End", "date", tooltip = "End of the subscription, empty for perpetual licenses"
    )
    private val columnAmountUsd = DataTableColumn("line-amount", "Amount USD", "num")
    private val columnPaidAmountUsd = DataTableColumn("line-amount-paid", "Paid Amount USD", "num")
    private val columnLocalAmount = DataTableColumn("line-amount-local", "Amount", "num")
    private val columnLocalPaidAmount = DataTableColumn("line-amount-local-paid", "Paid Amount", "num")
    private val columnDiscount = DataTableColumn("license-discount", "Discount")

    private val data = mutableListOf<Pair<PluginSale, PluginSaleItem>>()
    private var pluginId: PluginId? = null

    override val columns: List<DataTableColumn> = listOfNotNull(
        columnType,
        columnLicenseCount,
        columnLicenseIds,
        columnSubscriptionStart,
        columnSubscriptionEnd,
        columnAmountUsd,
        columnPaidAmountUsd,
        columnLocalAmount,
        columnLocalPaidAmount,
        columnDiscount,
    )

    override suspend fun init(data: PluginData) {
        super.init(data)
        this.pluginId = data.pluginId
    }

    override suspend fun process(sale: PluginSale) {
        for (lineItem in sale.lineItems) {
            data += sale to lineItem
        }
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        // ignored
    }

    override suspend fun createSections(): List<DataTableSection> {
        // the template shows a message instead of the empty table
        if (data.isEmpty()) {
            return emptyList()
        }

        val saleCurrency = data.first().first.amount.currency
        val amountTracker = PaymentAmountTracker(YearMonthDayRange.MAX, exchangeRates)
        val localAmountTracker = PaymentAmountTracker(YearMonthDayRange.MAX, ExchangeRates(saleCurrency.currencyCode))
        for ((sale, lineItem) in data) {
            amountTracker.add(sale.date, lineItem.amountUSD, lineItem.amount)
            localAmountTracker.add(sale.date, lineItem.amountUSD, lineItem.amount)
        }

        val rows = data.map { (sale, lineItem) ->
            SimpleDateTableRow(
                values = mapOf(
                    columnRefNum to sale.ref,
                    columnType to when (lineItem.type) {
                        PluginSaleItemType.New -> "New"
                        PluginSaleItemType.Renew -> "Renewal"
                    },
                    columnLicenseCount to lineItem.licenseIds.size.toBigInteger(),
                    columnLicenseIds to lineItem.licenseIds.map { licenseId ->
                        pluginId?.let { LinkedLicense(licenseId, it) } ?: licenseId
                    },
                    columnSubscriptionStart to (lineItem as? SubscriptionPluginSaleItem)?.subscriptionDates?.start,
                    columnSubscriptionEnd to (lineItem as? SubscriptionPluginSaleItem)?.subscriptionDates?.end,
                    columnAmountUsd to lineItem.amountUSD,
                    columnPaidAmountUsd to Marketplace.paidAmount(sale.date, lineItem.amountUSD),
                    columnLocalAmount to lineItem.amount,
                    columnLocalPaidAmount to Marketplace.paidAmount(sale.date, lineItem.amount),
                    // the descriptions usually contain the percentage, e.g. "20% continuity discount"
                    columnDiscount to lineItem.discountDescriptions.map { discount ->
                        val percent = discount.percent
                        when {
                            percent == null || "%" in discount.description -> discount.description
                            else -> "${discount.description} (${Formatters.formatPercentage(percent.toBigDecimal())})"
                        }
                    },
                ),
            )
        }

        val footer = SimpleRowGroup(
            SimpleDateTableRow(
                columnAmountUsd to amountTracker.totalAmountUSD,
                columnPaidAmountUsd to amountTracker.paidAmountUSD,
                columnLocalAmount to localAmountTracker.totalAmount,
                columnLocalPaidAmount to localAmountTracker.paidAmount,
            )
        )

        return listOf(SimpleTableSection(rows, null, footer = footer))
    }
}
