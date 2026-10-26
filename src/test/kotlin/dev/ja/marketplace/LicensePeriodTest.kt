/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.data.DataTableRow
import dev.ja.marketplace.data.LinkedRefNum
import dev.ja.marketplace.data.RenderedDataTable
import dev.ja.marketplace.data.licenses.LicenseStatus
import dev.ja.marketplace.data.licenses.LicenseTable
import dev.ja.marketplace.mcp.McpTestData
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The purchases of a license in the same sale are a single row with the period of all of them.
 * The pages of active and churned licenses show a license once, with its latest sale.
 */
class LicensePeriodTest {
    private val today = YearMonthDay(2026, 10, 24)
    private val customer = CustomerInfo(1, "Germany", CustomerType.Organization, "Customer")
    private val amount = FastMoney.of(100, MarketplaceCurrencies.USD)

    private fun item(validity: YearMonthDayRange, type: PluginSaleItemType = PluginSaleItemType.Renew): PluginSaleItem {
        return SubscriptionPluginSaleItem(type, listOf("license"), amount, amount, emptyList(), validity)
    }

    // a renewal with a purchase until a date and a purchase, which starts on the next day, e.g. to align the end dates
    private val sales = listOf(
        PluginSale(
            "ref-1", YearMonthDay(2025, 10, 24), amount, amount, LicensePeriod.Annual, customer, null,
            listOf(item(YearMonthDay(2025, 10, 24).rangeTo(YearMonthDay(2026, 10, 23)), PluginSaleItemType.New)),
        ),
        PluginSale(
            "ref-2", today, amount, amount, LicensePeriod.Annual, customer, null,
            listOf(
                item(YearMonthDay(2027, 4, 6).rangeTo(YearMonthDay(2027, 10, 23))),
                item(today.rangeTo(YearMonthDay(2027, 4, 5))),
            ),
        ),
    )

    private suspend fun render(table: LicenseTable): RenderedDataTable {
        val data = McpTestData.pluginData(sales = sales)
        table.init(data)
        data.getLicenses()!!.forEach { table.process(it) }
        return table.renderTable()
    }

    private fun RenderedDataTable.value(row: DataTableRow, columnId: String): Any? {
        return row.getValue(columns.single { it.id == columnId })
    }

    @Test
    suspend fun latestPurchaseShowsThePeriodOfItsSale() {
        val table = render(LicenseTable(statusFilter = LicenseStatus.Active, showOnlyLatestLicenseInfo = true, nowDate = today))
        val row = table.sections.single().rows.single()
        assertEquals(today, table.value(row, "license-validity"))
        assertEquals(YearMonthDay(2027, 10, 23), table.value(row, "license-validity-end"))
    }

    @Test
    suspend fun purchasesOfASaleAreOneRow() {
        val table = render(LicenseTable(nowDate = today))
        val rows = table.sections.single().rows
        assertEquals(listOf("ref-2", "ref-1"), rows.map { (table.value(it, "license-refnum") as LinkedRefNum).refNum })

        val renewal = rows.first()
        assertEquals(today, table.value(renewal, "license-validity"))
        assertEquals(YearMonthDay(2027, 10, 23), table.value(renewal, "license-validity-end"))
        assertEquals(FastMoney.of(200, MarketplaceCurrencies.USD), table.value(renewal, "sale-amount"))
    }
}
