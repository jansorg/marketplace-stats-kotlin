/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.chart.ChartDetail
import dev.ja.marketplace.data.chart.ChartWindow
import dev.ja.marketplace.mcp.McpTestData
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class ChartExportTest {
    private fun request(vararg parameters: Pair<String, String>): ChartExport.Request {
        return ChartExport.parse(parametersOf(parameters.associate { it.first to listOf(it.second) }))
    }

    private suspend fun table(vararg parameters: Pair<String, String>): ChartExport.Table {
        val values = ChartData().values(McpTestData.pluginData())
        return ChartExport.table(values, request(*parameters), 1 to "Test plugin")
    }

    private suspend fun csvRows(vararg parameters: Pair<String, String>): List<List<String>> {
        val csv = ChartExport.csv(table(*parameters))
        assertTrue(csv.endsWith("\r\n"))
        return csv.removeSuffix("\r\n").split("\r\n").map { line -> Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)").split(line) }
    }

    @Test
    fun linesWithWindowsAveragesAndPreviousYear() {
        val request = request("lines" to "sales, sales@avg3:year,mrr@previous-year:day,trials@avg12@previous-year:calendar-quarter", "detail" to "week")
        assertEquals(ChartDetail.Week, request.detail)
        assertEquals(ChartExport.Format.Csv, request.format)
        assertTrue(request.includeIncomplete)
        assertNull(request.from)

        val (sales, average, mrr, trials) = request.lines
        // the window follows the detail level by default
        assertEquals(ChartWindow.Week, sales.window)
        assertNull(sales.averageMonths)
        assertFalse(sales.previousYear)
        assertEquals(ChartWindow.Year, average.window)
        assertEquals(3, average.averageMonths)
        assertEquals("mrr", mrr.metric.id)
        assertTrue(mrr.previousYear)
        assertEquals(12, trials.averageMonths)
        assertTrue(trials.previousYear)
        assertEquals(ChartWindow.CalendarQuarter, trials.window)
    }

    @Test
    fun invalidParameters() {
        fun assertInvalid(vararg parameters: Pair<String, String>) {
            assertThrows<ChartExport.InvalidRequestException> { request(*parameters) }
        }
        assertInvalid()
        assertInvalid("lines" to " , ")
        assertInvalid("lines" to "unknown-metric")
        assertInvalid("lines" to "sales:unknown-window")
        assertInvalid("lines" to "sales@avg2")
        assertInvalid("lines" to "sales@avg3@avg3")
        assertInvalid("lines" to "sales@previous-year@avg3")
        assertInvalid("lines" to "sales@other")
        assertInvalid("lines" to "sales", "detail" to "hour")
        assertInvalid("lines" to "sales", "format" to "yaml")
        assertInvalid("lines" to "sales", "from" to "2025-13-01")
        assertInvalid("lines" to "sales", "from" to "2025-02-01", "to" to "2025-01-01")
        assertInvalid("lines" to "sales", "incomplete" to "no")
    }

    @Test
    fun tooManyLines() {
        val lines = ChartWindow.entries.flatMap { window -> listOf("sales", "sales@avg1", "sales@avg3", "sales@avg12", "trials").map { "$it:${window.id}" } }
        assertTrue(lines.size > ChartExport.MAX_LINES)
        assertThrows<ChartExport.InvalidRequestException> { request("lines" to lines.joinToString(",")) }
        assertEquals(ChartExport.MAX_LINES, request("lines" to lines.take(ChartExport.MAX_LINES).joinToString(",")).lines.size)
    }

    @Test
    fun windowsShorterThanTheDetailLevelHaveFinerDates() {
        assertEquals(ChartDetail.Day, ChartExport.lineDetail(ChartWindow.Day, ChartDetail.Month))
        assertEquals(ChartDetail.Week, ChartExport.lineDetail(ChartWindow.Week, ChartDetail.Year))
        assertEquals(ChartDetail.Month, ChartExport.lineDetail(ChartWindow.CalendarMonth, ChartDetail.Year))
        assertEquals(ChartDetail.Year, ChartExport.lineDetail(ChartWindow.Year, ChartDetail.Year))
        assertEquals(ChartDetail.Day, ChartExport.lineDetail(ChartWindow.Year, ChartDetail.Day))
    }

    @Test
    fun previousYearOfADay() {
        assertEquals(YearMonthDay(2024, 2, 29), ChartExport.previousYear(YearMonthDay(2025, 2, 28)))
        assertEquals(YearMonthDay(2025, 2, 28), ChartExport.previousYear(YearMonthDay(2026, 2, 28)))
        assertEquals(YearMonthDay(2025, 3, 10), ChartExport.previousYear(YearMonthDay(2026, 3, 10)))
    }

    @Test
    suspend fun csvWithLabelsUnitsAndStatus() {
        val rows = csvRows("lines" to "sales:calendar-year,sales@previous-year:calendar-year,mrr", "detail" to "year")
        assertEquals(
            listOf("date", "\"Sales before fees, calendar year (USD)\"", "\"Sales before fees, calendar year, previous year (USD)\"", "MRR (USD)", "status"),
            rows.first(),
        )
        // the data starts in 2024, the sales of 2024 only cover a part of the year
        val first = rows[1]
        assertEquals(listOf("2024-12-31", "400.00", ""), first.take(3))
        assertEquals("partial", first.last())
        // the previous year of 2025 is 2024
        val second = rows[2]
        assertEquals("2025-12-31", second[0])
        assertEquals("400.00", second[2])
        // the value of the previous year is partial
        assertEquals("partial", second.last())
        // the current year may still change
        val last = rows.last()
        assertEquals(YearMonthDay.now().asIsoString, last[0])
        assertEquals("incomplete", last.last())
    }

    @Test
    suspend fun datesOfTheRange() {
        val rows = csvRows("lines" to "sales", "from" to "2024-03-01", "to" to "2024-05-31").drop(1)
        assertEquals(listOf("2024-03-31", "2024-04-30", "2024-05-31"), rows.map { it.first() })
    }

    @Test
    suspend fun linesWithFinerDates() {
        // the sales per day in a chart of months have a value at each day, the monthly values only at the end of each month
        val table = table("lines" to "sales:day,sales", "from" to "2024-03-30", "to" to "2024-04-01")
        assertEquals(listOf("2024-03-30", "2024-03-31", "2024-04-01"), table.dates.map { it.asIsoString })
        val (daily, monthly) = table.columns
        assertEquals(ChartDetail.Day, daily.detail)
        assertTrue(daily.values.all { it != null })
        assertEquals(listOf(false, true, false), monthly.values.map { it != null })
    }

    @Test
    suspend fun withoutIncompleteValues() {
        val table = table("lines" to "sales:calendar-year", "detail" to "year", "incomplete" to "false")
        assertNull(table.columns.single().values.last())
        assertTrue(table.columns.single().incomplete.isEmpty())
    }

    @Test
    suspend fun jsonWithMetadata() {
        val table = table("lines" to "sales:calendar-year,trials@avg3:calendar-month,mrr", "detail" to "year", "format" to "json", "from" to "2024-01-01")
        val text = ChartData.json.encodeToString(ChartExportJson.serializer(), ChartExport.json(table, Instant.parse("2026-01-02T03:04:05.678Z")))
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals(1, json["pluginId"]!!.jsonPrimitive.int)
        assertEquals("Test plugin", json["pluginName"]!!.jsonPrimitive.content)
        assertEquals("2026-01-02T03:04:05Z", json["generated"]!!.jsonPrimitive.content)
        assertEquals("USD", json["currency"]!!.jsonPrimitive.content)
        assertEquals("year", json["detail"]!!.jsonPrimitive.content)
        assertEquals("2024-01-01", json["from"]!!.jsonPrimitive.content)
        assertFalse(json.containsKey("to"))
        assertFalse(json.containsKey("omitted"))

        val (sales, trials, mrr) = json["columns"]!!.jsonArray.map { it.jsonObject }
        assertEquals("sales:calendar-year", sales["id"]!!.jsonPrimitive.content)
        assertEquals("Sales before fees, calendar year", sales["label"]!!.jsonPrimitive.content)
        assertEquals("flow", sales["measure"]!!.jsonPrimitive.content)
        assertEquals("calendar-year", sales["window"]!!.jsonPrimitive.content)
        assertEquals(listOf("2024-12-31"), sales["partialDates"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(YearMonthDay.now().asIsoString), sales["incompleteDates"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(3, trials["averageMonths"]!!.jsonPrimitive.int)
        // the decimals to show the values, the values aren't rounded
        assertEquals(listOf(2, 1, 2), listOf(sales, trials, mrr).map { it["fractionDigits"]!!.jsonPrimitive.int })
        // the calendar months of a chart of years have their monthly dates
        assertEquals("month", trials["detail"]!!.jsonPrimitive.content)
        // values at a date have no window
        assertFalse(mrr.containsKey("window"))
        assertEquals("stock", mrr["measure"]!!.jsonPrimitive.content)

        // the rows are arrays of the date and the values, numbers keep their scale
        assertTrue(text.contains("[\"2024-12-31\",400.00,"), text)
    }

    @Test
    suspend fun summaryOfAllPlugins() {
        val values = ChartData().values(McpTestData.pluginData())
        val json = ChartExport.json(ChartExport.table(values, request("lines" to "sales"), null))
        assertNull(json.pluginId)
        assertEquals("Summary of all paid plugins", json.pluginName)
    }
}
