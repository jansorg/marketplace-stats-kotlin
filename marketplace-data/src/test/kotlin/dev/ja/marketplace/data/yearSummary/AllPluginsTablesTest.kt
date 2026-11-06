/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginTrial
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

    @Test
    suspend fun salesAndTrialsOfEachPluginInRecentPeriods() {
        // Sunday, the week started on Monday, December 9
        val today = YearMonthDay(2024, 12, 15)
        val table = AllPluginsRecentTable(exchangeRates, today)
        val salesA = pluginSales() + listOf(
            sale(customer(4), "d", YearMonthDay(2024, 12, 14), 20, LicensePeriod.Monthly),
            sale(customer(5), "e", YearMonthDay(2024, 12, 9), 30, LicensePeriod.Monthly),
            sale(customer(6), "f", YearMonthDay(2024, 12, 8), 40, LicensePeriod.Monthly),
        )
        val trialsA = listOf(
            PluginTrial("t1", today, customer(1)),
            PluginTrial("t2", YearMonthDay(2024, 12, 14), customer(2)),
            PluginTrial("t3", YearMonthDay(2024, 12, 1), customer(3)),
            PluginTrial("t4", YearMonthDay(2024, 11, 30), customer(4)),
        )
        table.add("Plugin B", emptyList(), listOf(PluginTrial("t5", today, customer(5))))
        table.add("Plugin A", salesA, trialsA)

        val (sales, trials) = table.renderTable().sections
        assertEquals("Sales", sales.title)
        assertEquals("Trials", trials.title)

        val salesRows = sales.rows.map { it as SimpleDateTableRow }
        assertEquals(listOf("Plugin A", "Plugin B"), salesRows.map { value(table, it, "plugin") })
        assertEquals(listOf(usd(10), usd(20), usd(60), usd(100)), periodValues(table, salesRows[0]))
        assertEquals(listOf(usd(0), usd(0), usd(0), usd(0)), periodValues(table, salesRows[1]))
        assertEquals(listOf(usd(10), usd(20), usd(60), usd(100)), periodValues(table, sales.footer!!.rows.single() as SimpleDateTableRow))

        val trialRows = trials.rows.map { it as SimpleDateTableRow }
        assertEquals(listOf(1, 1, 2, 3).map(Int::toBigInteger), periodValues(table, trialRows[0]))
        assertEquals(listOf(1, 0, 1, 1).map(Int::toBigInteger), periodValues(table, trialRows[1]))
        assertEquals(listOf(2, 1, 3, 4).map(Int::toBigInteger), periodValues(table, trials.footer!!.rows.single() as SimpleDateTableRow))
    }

    private fun periodValues(table: DataTable, row: SimpleDateTableRow): List<Any?> {
        return listOf("today", "yesterday", "week", "month").map { value(table, row, it) }
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
