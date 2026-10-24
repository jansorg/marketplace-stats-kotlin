/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.data.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import dev.ja.marketplace.client.model.LicensePeriod
import kotlinx.serialization.json.Json
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class DataTableJsonTest {
    @Test
    fun cellValues() {
        assertEquals(JsonNull, DataTableJson.cellValue(null))
        assertEquals(JsonNull, DataTableJson.cellValue(NoValue))
        assertEquals(JsonPrimitive(42), DataTableJson.cellValue(42.toBigInteger()))
        assertEquals(JsonPrimitive("2024-03-01"), DataTableJson.cellValue(YearMonthDay(2024, 3, 1)))
        assertEquals("12.35", Json.encodeToString(DataTableJson.cellValue(FastMoney.of(BigDecimal("12.345"), MarketplaceCurrencies.USD))))
        assertEquals("12.5", Json.encodeToString(DataTableJson.cellValue(PercentageValue(BigDecimal("12.5")))))
        assertEquals(
            """{"value":0.0,"incomplete":true}""",
            Json.encodeToString(DataTableJson.cellValue(IncompleteValue(PercentageValue.ZERO)))
        )
        val churn = LinkedChurnRate("", PercentageValue(BigDecimal("25")), YearMonthDay(2024, 1, 1), YearMonthDay(2024, 2, 1),
            LicensePeriod.Annual, 1, churnedCount = 1, previousActiveCount = 4)
        assertEquals("""{"percent":25.0,"churned":1,"previousActive":4}""", Json.encodeToString(DataTableJson.cellValue(churn)))
    }

    @Test
    fun columnKeysAreUnique() {
        val columns = listOf(
            DataTableColumn("sales", "Total Sales"),
            DataTableColumn("sales", "Fees"),
            DataTableColumn("customer-mrr", "MRR"),
            DataTableColumn("customer-mrr", "MRR"),
            DataTableColumn("month", null),
        )
        assertEquals(listOf("totalSales", "fees", "mrr", "mrr2", "month"), DataTableJson.columnKeys(columns))
    }

    @Test
    fun tableJson() {
        val month = DataTableColumn("month", null)
        val sales = DataTableColumn("sales", "Sales")
        val trials = DataTableColumn("trials", "Trials")
        val table = RenderedDataTable(
            TestTable(listOf(month, sales, trials)), listOf(
                SimpleTableSection(
                    rows = listOf(
                        SimpleDateTableRow(mapOf(month to "2024-01", sales to FastMoney.of(10, MarketplaceCurrencies.USD), trials to 2.toBigInteger())),
                        SimpleDateTableRow(
                            values = mapOf(month to "2024-02", sales to FastMoney.of(20, MarketplaceCurrencies.USD), trials to 3.toBigInteger()),
                            tooltips = mapOf(trials to "1 of 3 trials converted", sales to "Sales of the month"),
                        ),
                    ),
                    footer = SimpleRowGroup(SimpleDateTableRow(sales to FastMoney.of(30, MarketplaceCurrencies.USD))),
                )
            )
        )

        val json = DataTableJson.toJson(table, today = YearMonthDay(2024, 2, 10))
        assertEquals(
            """[{"key":"month","title":"month"},{"key":"sales","title":"Sales","unit":"USD"},{"key":"trials","title":"Trials"}]""",
            Json.encodeToString(json["columns"])
        )
        val section = json["sections"]!!.jsonArray.single().jsonObject
        assertEquals("""[["2024-01",10.0,2],["2024-02",20.0,3]]""", Json.encodeToString(section["rows"]))
        assertEquals("""[1]""", Json.encodeToString(section["incompleteRows"]))
        // only tooltips with counts
        assertEquals("""[{"row":1,"column":"trials","text":"1 of 3 trials converted"}]""", Json.encodeToString(section["notes"]))
        assertEquals("""[[null,30.0,null]]""", Json.encodeToString(section["footer"]))

        // the footer is dropped, if rows are removed
        val filtered = DataTableJson.toJson(table, YearMonthDayRange.ofMonth(2024, 1)).getValue("sections").jsonArray.single().jsonObject
        assertEquals(1, filtered["rows"]!!.jsonArray.size)
        assertNull(filtered["footer"])
    }

    private class TestTable(override val columns: List<DataTableColumn>) : DataTable {
        override val id: String? = null
        override val title: String? = null
        override val cssClass: String? = null
        override val header: DataRowGroup? = null

        override suspend fun init(data: PluginData) {}

        override suspend fun process(licenseInfo: LicenseInfo) {}

        override suspend fun renderTable(): RenderedDataTable = throw UnsupportedOperationException()
    }

    @Test
    fun rowPeriods() {
        assertEquals(YearMonthDayRange.of(YearMonthDay(2024, 3, 1)), DataTableJson.rowPeriod(YearMonthDay(2024, 3, 1)))
        assertEquals(YearMonthDayRange.ofMonth(2024, 2), DataTableJson.rowPeriod("2024-02"))
        assertEquals(YearMonthDayRange.ofYear(2024), DataTableJson.rowPeriod(2024))
        assertNull(DataTableJson.rowPeriod("DE"))
        assertNull(DataTableJson.rowPeriod(null))
    }
}
