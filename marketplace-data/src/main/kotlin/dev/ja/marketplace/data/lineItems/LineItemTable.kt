/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.lineItems

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItem
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.PaymentAmountTracker
import dev.ja.marketplace.exchangeRate.ExchangeRates

class LineItemsTable : SimpleDataTable("Line Items", "line-items", "table-column-wide"), MarketplaceDataSink {
    private val columnRefNum = DataTableColumn("license-refnum", "Ref Num", "col-right")
    private val columnPurchaseDate = DataTableColumn("line-date", "Purchase", "date")
    private val columnLicenseCount = DataTableColumn("license-count", "Licenses", "num")
    private val columnAmountUsd = DataTableColumn("line-amount", "Amount USD", "num")
    private val columnPaidAmountUsd = DataTableColumn("line-amount-paid", "Paid Amount USD", "num")
    private val columnLocalAmount = DataTableColumn("line-amount-local", "Amount", "num")
    private val columnLocalPaidAmount = DataTableColumn("line-amount-local-paid", "Paid Amount", "num")
    private val columnDiscount = DataTableColumn("license-discount", "Discount", "num")

    private val data = mutableListOf<Pair<PluginSale, PluginSaleItem>>()

    override val columns: List<DataTableColumn> = listOfNotNull(
        columnPurchaseDate,
        columnLicenseCount,
        columnAmountUsd,
        columnPaidAmountUsd,
        columnLocalAmount,
        columnLocalPaidAmount,
        columnDiscount,
    )

    override suspend fun process(sale: PluginSale) {
        for (lineItem in sale.lineItems) {
            data += sale to lineItem
        }
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        // ignored
    }

    override suspend fun createSections(): List<DataTableSection> {
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
                    columnLicenseCount to lineItem.licenseIds.size,
                    columnPurchaseDate to sale.date,
                    columnAmountUsd to lineItem.amountUSD,
                    columnPaidAmountUsd to Marketplace.paidAmount(sale.date, lineItem.amountUSD),
                    columnLocalAmount to lineItem.amount,
                    columnLocalPaidAmount to Marketplace.paidAmount(sale.date, lineItem.amount),
                    columnDiscount to lineItem.discountDescriptions.joinToString(", ") { it.description },
                ),
            )
        }

        val footer = SimpleRowGroup(
            SimpleDateTableRow(
                columnAmountUsd to amountTracker.totalAmountUSD,
                columnPaidAmountUsd to amountTracker.paidAmountUSD,
                columnLocalAmount to localAmountTracker.totalAmount,
                columnLocalPaidAmount to localAmountTracker .paidAmount,
            )
        )

        return listOf(SimpleTableSection(rows, null, footer = footer))
    }
}
