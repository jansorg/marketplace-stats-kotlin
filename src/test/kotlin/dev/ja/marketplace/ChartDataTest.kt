/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.chart.ChartDetail
import dev.ja.marketplace.data.chart.ChartWindow
import dev.ja.marketplace.mcp.McpTestData
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChartDataTest {
    private suspend fun json(metrics: List<String>, window: ChartWindow, detail: ChartDetail): JsonObject {
        val values = ChartData().values(McpTestData.pluginData())
        val json = ChartData.json.encodeToString(ChartDataResponse.serializer(), ChartData.chartData(values, metrics, window, detail))
        return Json.parseToJsonElement(json).jsonObject
    }

    private fun JsonObject.dates(): List<String> = this["dates"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.value(metric: String, date: String): String {
        val values = this["series"]!!.jsonObject[metric]!!.jsonObject["values"]!!.jsonArray
        assertEquals(dates().size, values.size)
        return values[dates().indexOf(date)].jsonPrimitive.content
    }

    @Test
    suspend fun sumsOfTheYearBeforeEachDay() {
        val json = json(listOf("sales", "trials"), ChartWindow.Year, ChartDetail.Day)
        // the days from the first year with data
        assertEquals("2024-01-01", json.dates().first())

        // the sales of 2024 at the end of the year, and the sales of the year before a day of 2025
        assertEquals("400.00", json.value("sales", "2024-12-31"))
        assertEquals("310.00", json.value("sales", "2025-03-10"))
        assertEquals("1", json.value("trials", "2024-12-31"))
        assertEquals("2", json.value("trials", "2025-02-28"))
        // the first trial is more than a year before the day
        assertEquals("1", json.value("trials", "2025-03-01"))
    }

    @Test
    suspend fun metricsWithTitleUnitAndMeasure() {
        val series = json(listOf("sales", "mrr", "unknown"), ChartWindow.Month, ChartDetail.Month)["series"]!!.jsonObject
        assertEquals(setOf("sales", "mrr"), series.keys)
        val sales = series["sales"]!!.jsonObject
        assertEquals("Sales before fees", sales["title"]!!.jsonPrimitive.content)
        assertEquals("USD", sales["unit"]!!.jsonPrimitive.content)
        assertEquals("flow", sales["measure"]!!.jsonPrimitive.content)
        assertEquals("true", sales["usesWindow"]!!.jsonPrimitive.content)
        assertEquals("false", series["mrr"]!!.jsonObject["usesWindow"]!!.jsonPrimitive.content)
    }

    @Test
    suspend fun valuesAreNumbersWithTheirScale() {
        val values = ChartData().values(McpTestData.pluginData())
        val json = ChartData.json.encodeToString(ChartDataResponse.serializer(), ChartData.chartData(values, listOf("sales", "trials"), ChartWindow.Year, ChartDetail.Year))
        // amounts keep their two decimals, counts have none, values without an average have no property of it
        assertTrue(json.contains("\"values\":[400.00,"), json)
        assertTrue(json.contains("\"values\":[1,"), json)
        assertFalse(json.contains("averageMonths"), json)
    }

    @Test
    suspend fun metricsOfThePlugin() {
        val metrics = ChartData.metrics(ChartData().values(McpTestData.pluginData()))
        assertEquals("sales", metrics.first().id)
        assertEquals("Sales", metrics.first().group)
        assertFalse(metrics.any { it.id.endsWith(".Unknown") })
        // the tooltips of the menu to add metrics, each metric has its own
        assertTrue(metrics.all { it.description.isNotBlank() })
        assertEquals(metrics.size, metrics.map { it.description }.toSet().size)
    }

    @Test
    suspend fun valuesAtTheEndOfEachMonth() {
        val dates = json(listOf("sales"), ChartWindow.Month, ChartDetail.Month).dates()
        assertEquals(listOf("2024-01-31", "2024-02-29", "2024-03-31"), dates.take(3))
        assertFalse(dates.contains("2024-03-30"))
        // today is the last date
        assertEquals(YearMonthDay.now().asIsoString, dates.last())
    }

    @Test
    suspend fun sumsOfTheFirstYearArePartial() {
        // the data starts in 2024, the sums of 2024 only cover a part of the year
        val series = json(listOf("sales", "mrr"), ChartWindow.CalendarYear, ChartDetail.Year)["series"]!!.jsonObject
        assertEquals("1", series["sales"]!!.jsonObject["partialUntil"]!!.jsonPrimitive.content)
        // values at a day aren't partial
        assertEquals("0", series["mrr"]!!.jsonObject["partialUntil"]!!.jsonPrimitive.content)
        // values per day aren't partial after the first day with data
        val days = json(listOf("sales"), ChartWindow.Day, ChartDetail.Day)
        val partialDays = days["series"]!!.jsonObject["sales"]!!.jsonObject["partialUntil"]!!.jsonPrimitive.content.toInt()
        assertEquals("2024-01-01", days.dates().first())
        assertEquals(McpTestData.pluginData().getFirstActivityDate()!!.asIsoString, days.dates()[partialDays])
    }

    @Test
    suspend fun valuesAtTheEndOfEachYear() {
        val json = json(listOf("customer-type-share.Individual"), ChartWindow.Year, ChartDetail.Year)
        assertEquals("2024-12-31", json.dates().first())
        assertEquals(YearMonthDay.now().asIsoString, json.dates().last())

        // the shares of the customer types of 2024
        assertEquals("25.00", json.value("customer-type-share.Individual", "2024-12-31"))
    }
}
