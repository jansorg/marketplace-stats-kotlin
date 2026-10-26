/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customerType

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.data.ChartPeriod
import dev.ja.marketplace.data.ChartValues
import dev.ja.marketplace.mcp.McpTestData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CustomerTypeTableTest {
    @Test
    suspend fun shareOfEachCustomerTypePerMonth() {
        val data = McpTestData.pluginData()
        val table = CustomerTypeTable(today = YearMonthDay(2025, 2, 10))
        table.init(data)
        data.getSales()!!.forEach { table.process(it) }

        val rendered = table.renderTable()
        val column = rendered.columns.single { it.rowHistoryPeriod != null }
        val histories = rendered.sections.single().rows.associate { row ->
            row.getValue(rendered.columns.first()) as CustomerType to ChartValues.history(ChartPeriod.Month, row.getChartHistory(column)!!, YearMonthDay(2025, 2, 10))
        }

        // the months with sales, the current month is incomplete and located at today
        assertEquals(
            """{"unit":"%","points":[["2024-03-31",100.00,false],["2024-11-30",0.00,false],["2025-02-10",100.00,true]]}""",
            histories[CustomerType.Individual],
        )
        assertEquals(
            """{"unit":"%","points":[["2024-03-31",0.00,false],["2024-11-30",100.00,false],["2025-02-10",0.00,true]]}""",
            histories[CustomerType.Organization],
        )
    }
}
