/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.data.licenses.LicenseStatus
import dev.ja.marketplace.data.licenses.LicenseTable
import dev.ja.marketplace.mcp.McpTestData
import kotlinx.coroutines.runBlocking
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The pages of active and churned licenses show a license once, with the period of all purchases of its latest sale.
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

    @Test
    fun latestPurchaseShowsThePeriodOfItsSale() {
        val table = LicenseTable(statusFilter = LicenseStatus.Active, showOnlyLatestLicenseInfo = true, nowDate = today)
        val rendered = runBlocking {
            val data = McpTestData.pluginData(sales = sales)
            table.init(data)
            data.getLicenses()!!.forEach { table.process(it) }
            table.renderTable()
        }

        val row = rendered.sections.single().rows.single()
        assertEquals(today, row.getValue(rendered.columns.single { it.id == "license-validity" }))
        assertEquals(YearMonthDay(2027, 10, 23), row.getValue(rendered.columns.single { it.id == "license-validity-end" }))
    }
}
