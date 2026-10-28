/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.DataTable
import dev.ja.marketplace.data.IncompleteValue
import dev.ja.marketplace.data.SimpleDateTableRow
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.customer
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.licenses
import dev.ja.marketplace.data.subscriptions.SubscriptionTestData.sale
import dev.ja.marketplace.exchangeRate.EcbExchangeRateData
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import javax.money.MonetaryAmount

class AllPluginsTablesTest {
    private val exchangeRates = ExchangeRates("USD", EcbExchangeRateData())

    /** The sales of a plugin, the license IDs of different plugins are the same. */
    private fun pluginSales(): List<PluginSale> = listOf(
        sale(customer(1), "a", YearMonthDay(2024, 3, 1), 120, LicensePeriod.Annual),
        sale(customer(2), "b", YearMonthDay(2024, 12, 15), 10, LicensePeriod.Monthly),
        // expired before the end of the year
        sale(customer(3), "c", YearMonthDay(2024, 1, 1), 10, LicensePeriod.Monthly),
    )

    @Test
    suspend fun summaryOfTheYears() {
        val table = AllPluginsSummaryTable(exchangeRates)
        repeat(2) {
            val sales = pluginSales()
            table.add(sales, licenses(*sales.toTypedArray()), emptyList())
        }

        val row = rows(table).single { it.values.values.contains(2024) }
        assertEquals(usd(280), value(table, row, "sales"))
        // the licenses of both plugins count, although their IDs are the same
        assertEquals(usd(40), value(table, row, "mrr"))
        assertEquals(usd(480), value(table, row, "arr"))
    }

    @Test
    suspend fun valuesOfTheDaysOfTheMonth() {
        val today = YearMonthDay(2024, 12, 20)
        val table = AllPluginsMonthTable(exchangeRates, today)
        repeat(2) {
            val sales = pluginSales()
            table.add(sales, licenses(*sales.toTypedArray()), emptyList())
        }

        val rows = rows(table)
        assertEquals(20, rows.size)
        val before = rows.single { it.values.values.contains(YearMonthDay(2024, 12, 14)) }
        assertEquals(usd(20), value(table, before, "mrr"))
        assertEquals(usd(0), value(table, before, "sales"))
        val day = rows.single { it.values.values.contains(YearMonthDay(2024, 12, 15)) }
        assertEquals(usd(20), value(table, day, "sales"))
        assertEquals(usd(40), value(table, day, "mrr"))
        assertEquals(usd(480), value(table, day, "arr"))
    }

    private suspend fun rows(table: DataTable): List<SimpleDateTableRow> {
        return table.renderTable().sections.single().rows.map { it as SimpleDateTableRow }
    }

    private fun value(table: DataTable, row: SimpleDateTableRow, columnId: String): Any? {
        val value = row.values[table.columns.single { it.id == columnId }]
        return (if (value is IncompleteValue) value.value else value).let { (it as? MonetaryAmount)?.let(FastMoney::from) ?: it }
    }

    private fun usd(amount: Int): MonetaryAmount = FastMoney.of(amount, "USD")
}
