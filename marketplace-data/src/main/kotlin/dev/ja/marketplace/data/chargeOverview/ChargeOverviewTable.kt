/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.chargeOverview

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItem
import dev.ja.marketplace.data.*
import javax.money.CurrencyUnit
import javax.money.MonetaryAmount

class ChargeOverviewTable : SimpleDataTable("Charges", "charges", "table-column-wide"), MarketplaceDataSink {
    private val columnRefNum = DataTableColumn("sale-refnum", "RefNum", "col-right")
    private val columnPurchaseDate = DataTableColumn("sale-date", "Purchase", "date")
    private val columnCustomerId = DataTableColumn("customer", "ID")
    private val columnCustomerName = DataTableColumn("customer", "Name", cssStyle = "width: 20%; max-width: 35%", cssClass = "noprint")
    private val columnReseller = DataTableColumn("reseller", "Reseller")
    private val columnAmountExpected = DataTableColumn("sale-amount-expected", "Expected Sale", "num")
    private val columnAmountActual = DataTableColumn("sale-amount", "Actual Sale", "num")
    private val columnOvercharged = DataTableColumn("charge-too-much", null, "num red")
    private val columnPaidExpected = DataTableColumn("sale-amount-expected", "Expected Paid", "num")
    private val columnPaidActual = DataTableColumn("sale-amount", "Actually Paid", "num")
    private val columnPaidOvercharged = DataTableColumn("paid-too-less", null, "num red")

    override val columns: List<DataTableColumn> = listOf(
        columnRefNum,
        columnPurchaseDate,
        columnCustomerId,
        columnCustomerName,
        columnReseller,
        columnAmountExpected,
        columnAmountActual,
        columnOvercharged,
        columnPaidExpected,
        columnPaidActual,
        columnPaidOvercharged
    )

    override val alwaysShowMainColumns: Boolean = true

    private val lineItems = mutableListOf<Pair<PluginSale, PluginSaleItem>>()

    private var pluginId: PluginId? = null

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.pluginId = data.pluginId
    }

    override suspend fun process(sale: PluginSale) {
        for (lineItem in sale.lineItems) {
            if (lineItem.discountDescriptions.any { it.isResellerDiscount }) {
                lineItems += sale to lineItem
            }
        }
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        // ignored
    }

    override suspend fun createSections(): List<DataTableSection> {
        val overchargeTotal = mutableMapOf<CurrencyUnit, MonetaryAmount>()
        for (lineItem in lineItems) {
            val overcharge = withoutJetBrainsCommission(lineItem.first.date, overcharge(lineItem.second.amount))
            overchargeTotal.compute(lineItem.second.amount.currency) { _, value ->
                value?.add(overcharge) ?: overcharge
            }
        }

        val rows = lineItems.map { (sale, lineItem) ->
            SimpleDateTableRow(
                values = mapOf(
                    columnRefNum to LinkedRefNum(sale.ref, pluginId!!),
                    columnPurchaseDate to sale.date,
                    columnCustomerId to sale.customer.code,
                    columnCustomerName to sale.customer.name,
                    columnReseller to sale.reseller?.name,
                    // before JetBrains commission
                    columnAmountActual to lineItem.amount,
                    columnAmountExpected to expectedAmount(lineItem.amount),
                    columnOvercharged to overcharge(lineItem.amount),
                    // after JetBrains commission
                    columnPaidActual to withoutJetBrainsCommission(sale.date, lineItem.amount),
                    columnPaidExpected to expectedAmount(withoutJetBrainsCommission(sale.date, lineItem.amount)),
                    columnPaidOvercharged to overcharge(withoutJetBrainsCommission(sale.date, lineItem.amount)),
                ),
            )
        }

        return listOf(
            SimpleTableSection(
                rows,
                footer = SimpleRowGroup(
                    SimpleDateTableRow(
                        mapOf(
                            columnPaidOvercharged to overchargeTotal.values
                        )
                    )
                )
            )
        )
    }

    private fun withoutJetBrainsCommission(saleDate: YearMonthDay, monetaryAmount: MonetaryAmount): MonetaryAmount {
        return monetaryAmount.minus(Marketplace.feeAmount(saleDate, monetaryAmount))
    }

    private fun expectedAmount(monetaryAmount: MonetaryAmount): MonetaryAmount = monetaryAmount.divide(0.95)

    private fun overcharge(monetaryAmount: MonetaryAmount) = expectedAmount(monetaryAmount).minus(monetaryAmount).negate()
}