/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.summary

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.CustomerInfo
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItemDiscount
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
import dev.ja.marketplace.data.DataTableColumn
import dev.ja.marketplace.data.IncompleteValue
import dev.ja.marketplace.data.MarketplaceDataSink
import dev.ja.marketplace.data.PluginData
import dev.ja.marketplace.data.SimpleDataTable
import dev.ja.marketplace.data.SimpleDateTableRow
import dev.ja.marketplace.data.chargeOverview.ChargeOverviewTable
import dev.ja.marketplace.data.timeSpanSummary.TimeSpanSummaryTable
import dev.ja.marketplace.data.yearSummary.YearlySummaryTable
import dev.ja.marketplace.mcp.McpTestData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import javax.money.MonetaryAmount

class SummaryTablesTest {
    @Test
    suspend fun yearlySummary() {
        val table = YearlySummaryTable()
        val rows = render(table, McpTestData.pluginData())

        val year2024 = rows.single { it.values[column(table, "year")] == 2024 }
        assertAmount(400, year2024.values[column(table, "sales")])
        assertAmount(400, year2024.values[column(table, "arr")])
        assertEquals(BigInteger.ONE, year2024.values[column(table, "trials")])

        // all licenses expired before the end of 2025
        val year2025 = rows.single { it.values[column(table, "year")] == 2025 }
        assertAmount(10, year2025.values[column(table, "sales")])
        assertAmount(0, year2025.values[column(table, "arr")])
    }

    @Test
    suspend fun timeSpanSummary() {
        val table = TimeSpanSummaryTable(YearMonthDayRange(YearMonthDay(2024, 3, 1), YearMonthDay(2024, 3, 10)), "Days")
        val data = McpTestData.pluginData()
        val rows = render(table, data)

        assertEquals(10, rows.size)
        val sales = table.columns.single { it.title == "Sales" }
        val downloads = table.columns.single { it.title == "↓" }
        val trials = table.columns.single { it.title == "Trials" }
        assertAmount(100, rows.single { it.values.values.contains(YearMonthDay(2024, 3, 10)) }.values[sales])
        val first = rows.single { it.values.values.contains(YearMonthDay(2024, 3, 1)) }
        assertEquals(BigInteger.valueOf(40), first.values[downloads])
        assertEquals(BigInteger.ONE, first.values[trials])

        val footer = table.renderTable().sections.single().footer!!.rows.single() as SimpleDateTableRow
        assertAmount(100, footer.values[sales])
        assertEquals(BigInteger.valueOf(40), footer.values[downloads])
        assertEquals(BigInteger.ONE, footer.values[trials])
    }

    @Test
    suspend fun chargesOfResellers() {
        val sales = listOf(
            resellerSale("ref-1", 90, PluginSaleItemDiscount("Reseller discount", 10.0)),
            // without a percentage, the discount of a reseller is 5 %
            resellerSale("ref-2", 95, PluginSaleItemDiscount("Reseller discount")),
            // not a sale of a reseller
            resellerSale("ref-3", 50, PluginSaleItemDiscount("Promotion", 50.0)),
        )
        val table = ChargeOverviewTable()
        val rows = render(table, McpTestData.pluginData(sales = sales))

        assertEquals(2, rows.size)
        val expected = table.columns.single { it.title == "Expected Sale" }
        val overcharged = table.columns.single { it.id == "charge-too-much" }
        assertAmount(100, rows[0].values[expected])
        assertAmount(-10, rows[0].values[overcharged])
        assertAmount(100, rows[1].values[expected])
        assertAmount(-5, rows[1].values[overcharged])
    }

    private suspend fun <T> render(table: T, data: PluginData): List<SimpleDateTableRow> where T : SimpleDataTable, T : MarketplaceDataSink {
        table.init(data)
        data.getSales()!!.forEach { table.process(it) }
        data.getLicenses()!!.forEach { table.process(it) }
        return table.renderTable().sections.first().rows.map { it as SimpleDateTableRow }
    }

    private fun column(table: SimpleDataTable, id: String): DataTableColumn = table.columns.single { it.id == id }

    private fun assertAmount(expected: Int, actual: Any?) {
        val amount = (if (actual is IncompleteValue) actual.value else actual) as MonetaryAmount
        assertEquals(
            BigDecimal(expected).setScale(2),
            amount.number.numberValue(BigDecimal::class.java).setScale(2, RoundingMode.HALF_UP),
        )
    }

    private fun resellerSale(ref: String, amountUSD: Int, discount: PluginSaleItemDiscount): PluginSale {
        val amount = org.javamoney.moneta.FastMoney.of(amountUSD, MarketplaceCurrencies.USD)
        val date = YearMonthDay(2024, 5, 1)
        val lineItem = SubscriptionPluginSaleItem(
            PluginSaleItemType.New, listOf("license-$ref"), amount, amount, listOf(discount), date.rangeTo(date.add(1, 0, -1)),
        )
        val customer = CustomerInfo(10, "Germany", CustomerType.Organization, "Organization")
        return PluginSale(ref, date, amount, amount, LicensePeriod.Annual, customer, null, listOf(lineItem))
    }
}
