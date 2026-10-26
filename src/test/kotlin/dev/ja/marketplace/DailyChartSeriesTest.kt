/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.yearSummary.AllPluginsDailyValues
import dev.ja.marketplace.mcp.McpTestData
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DailyChartSeriesTest {
    @Test
    suspend fun sumsOfTheYears() {
        val json = Json.parseToJsonElement(DailyChartSeries().json(McpTestData.pluginData(), DailyChartSeries.Granularity.Day)).jsonObject
        val dates = json["dates"]!!.jsonArray.map { it.jsonPrimitive.content }
        // the days of the overview, from the first month with data
        assertEquals("2024-03-01", dates.first())

        val series = json["series"]!!.jsonObject
        fun value(id: String, date: String): String {
            val values = series[id]!!.jsonObject["values"]!!.jsonArray
            assertEquals(dates.size, values.size)
            return values[dates.indexOf(date)].jsonPrimitive.content
        }

        // the sales of 2024 at the end of the year, and the sales of the year before a day of 2025
        assertEquals("400.00", value(AllPluginsDailyValues.SALES, "2024-12-31"))
        assertEquals("310.00", value(AllPluginsDailyValues.SALES, "2025-03-10"))
        assertEquals("1", value(AllPluginsDailyValues.TRIALS, "2024-12-31"))
        assertEquals("2", value(AllPluginsDailyValues.TRIALS, "2025-02-28"))
        // the first trial is more than a year before the day
        assertEquals("1", value(AllPluginsDailyValues.TRIALS, "2025-03-01"))
    }

    @Test
    suspend fun valuesAtTheEndOfEachMonth() {
        val json = Json.parseToJsonElement(DailyChartSeries().json(McpTestData.pluginData(), DailyChartSeries.Granularity.Month)).jsonObject
        val dates = json["dates"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("2024-03-31", "2024-04-30", "2024-05-31"), dates.take(3))
        assertTrue(dates.contains("2024-12-31"))
        // today is the last date
        assertEquals(YearMonthDay.now().asIsoString, dates.last())
    }

    @Test
    suspend fun valuesAtTheEndOfEachYear() {
        val json = Json.parseToJsonElement(DailyChartSeries().json(McpTestData.pluginData(), DailyChartSeries.Granularity.Year)).jsonObject
        val dates = json["dates"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("2024-12-31", dates.first())
        assertEquals(YearMonthDay.now().asIsoString, dates.last())

        // the shares of the customer types of 2024
        val shares = json["series"]!!.jsonObject["customer-type-share.year.Individual"]!!.jsonObject["values"]!!.jsonArray
        assertEquals("25.00", shares.first().jsonPrimitive.content)
    }
}
