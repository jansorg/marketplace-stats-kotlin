/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.daySummary

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.LicensingType
import dev.ja.marketplace.client.model.MarketplacePluginInfo
import dev.ja.marketplace.data.SimpleDateTableRow
import dev.ja.marketplace.mcp.McpTestData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DaySummaryTableTest {
    private val year2024 = YearMonthDayRange(YearMonthDay(2024, 1, 1), YearMonthDay(2024, 12, 31))

    @Test
    suspend fun subscriptionPlugin() {
        assertEquals(listOf(LicensePeriod.Annual, LicensePeriod.Monthly), periods(LicensingType.Subscription, year2024))
    }

    @Test
    suspend fun perpetualPlugin() {
        // the annual sales of the range are still shown
        assertEquals(listOf(LicensePeriod.Annual, LicensePeriod.Perpetual), periods(LicensingType.Perpetual, year2024))

        val noSales = YearMonthDayRange.of(YearMonthDay(2023, 1, 1))
        assertEquals(listOf(LicensePeriod.Perpetual), periods(LicensingType.Perpetual, noSales))
    }

    @Test
    suspend fun allPeriods() {
        // unknown licensing, e.g. if the info couldn't be loaded
        assertEquals(LicensePeriod.entries - LicensePeriod.Unknown, periods(null, year2024))

        // subscriptions and perpetual licenses
        assertEquals(LicensePeriod.entries - LicensePeriod.Unknown, periods(LicensingType.Perpetual, year2024, LicensePeriod.Annual, LicensePeriod.Monthly))
    }

    private suspend fun periods(
        licensing: LicensingType?,
        dateRange: YearMonthDayRange,
        vararg apiPeriods: LicensePeriod,
    ): List<LicensePeriod> {
        val info = licensing?.let { MarketplacePluginInfo("PCODE", "Plugin", apiPeriods.toList(), licensingType = it) }
        val data = McpTestData.pluginData(marketplacePluginInfo = info)

        val table = DaySummaryTable(dateRange, "Summary")
        table.init(data)
        data.getSales()!!.forEach { table.process(it) }

        return table.renderTable().sections.first().rows
            .map { row -> (row as SimpleDateTableRow).values.values.filterIsInstance<LicensePeriod>().single() }
            .distinct()
    }
}
