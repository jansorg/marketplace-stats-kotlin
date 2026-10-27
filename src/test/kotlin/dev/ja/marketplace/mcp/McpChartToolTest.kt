/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import dev.ja.marketplace.chartImage.ChartColor
import dev.ja.marketplace.chartImage.ChartExtreme
import dev.ja.marketplace.chartImage.ChartMarkerKind
import dev.ja.marketplace.chartImage.ChartStyle
import dev.ja.marketplace.chartImage.ChartTheme
import dev.ja.marketplace.data.chart.ChartDetail
import dev.ja.marketplace.data.chart.ChartMetric
import dev.ja.marketplace.data.chart.ChartWindow
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.time.LocalDate
import javax.imageio.ImageIO

class McpChartToolTest {
    private val tools = MarketplaceMcpTools(McpTestData.provider)
    private val today = LocalDate.of(2025, 10, 26)

    private fun arguments(builder: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("pluginId", McpTestData.PLUGIN_ID)
        builder()
    }

    private fun series(vararg items: JsonObject) = JsonArray(items.toList())

    private fun metric(id: String, builder: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("metric", id)
        builder()
    }

    private fun parse(builder: JsonObjectBuilder.() -> Unit) = ChartRequest.parse(buildJsonObject(builder), today)

    @Test
    fun parseDefaults() {
        val request = parse { put("series", series(metric("sales"))) }
        assertEquals(listOf(ChartSeriesRequest(ChartMetric.Sales)), request.series)
        assertEquals(ChartDetail.Month, request.detail)
        assertNull(request.window, "the window follows the level")
        assertEquals(ChartStyle.Lines, request.style)
        assertNull(request.range.start)
        assertNull(request.range.end)
        assertTrue(request.showIncomplete)
        assertFalse(request.previousYear)
        assertEquals(ChartFormat.Png, request.format)
        assertEquals(ChartRequest.DEFAULT_WIDTH, request.width)
    }

    @Test
    fun parseAllOptions() {
        val request = parse {
            put("series", series(
                metric("trials") { put("window", "year"); put("style", "bars"); put("color", "#123456") },
                metric("sales") { put("average", 3) },
                metric("mrr@avg12"),
            ))
            put("detail", "Week")
            put("window", "calendar-quarter")
            put("style", "bars")
            put("range", "2024")
            put("previousYear", true)
            put("showIncomplete", false)
            put("extremes", buildJsonArray { add("min"); add("max") })
            put("hideAxisValues", true)
            put("logScale", true)
            put("theme", "Dark")
            put("markers", "release,events")
            put("title", "Trials")
            put("format", "svg")
            put("width", 640)
            put("height", 320)
            put("scale", 2)
        }
        assertEquals(ChartSeriesRequest(ChartMetric.Trials, ChartWindow.Year, null, ChartStyle.Bars, ChartColor.parse("#123456")), request.series[0])
        assertEquals(3, request.series[1].average)
        assertEquals(ChartSeriesRequest(ChartMetric.Mrr, average = 12), request.series[2])
        assertEquals(ChartDetail.Week, request.detail)
        assertEquals(ChartWindow.CalendarQuarter, request.window)
        assertEquals(ChartStyle.Bars, request.style)
        assertEquals(LocalDate.of(2024, 1, 1), request.range.start)
        assertEquals(LocalDate.of(2024, 12, 31), request.range.end)
        assertTrue(request.previousYear)
        assertFalse(request.showIncomplete)
        assertEquals(setOf(ChartExtreme.Min, ChartExtreme.Max), request.extremes)
        assertTrue(request.hideAxisValues)
        assertTrue(request.logScale)
        assertEquals(ChartTheme.Dark, request.theme)
        assertEquals(setOf(ChartMarkerKind.Release, ChartMarkerKind.Event), request.markers)
        assertEquals("Trials", request.title)
        assertEquals(ChartFormat.Svg, request.format)
        assertEquals(640 to 320, request.width to request.height)
        assertEquals(2, request.scale)
    }

    @Test
    fun parseRanges() {
        val sales = series(metric("sales"))
        assertEquals(today to today, parse { put("series", sales); put("range", "today") }.range.let { it.start to it.end })
        assertEquals(LocalDate.of(2025, 10, 20) to today, parse { put("series", sales); put("range", "past-7-days") }.range.let { it.start to it.end })
        assertEquals(LocalDate.of(2025, 10, 1) to today, parse { put("series", sales); put("range", "this-month") }.range.let { it.start to it.end })
        val dates = parse { put("series", sales); put("startDate", "2025-02-01") }.range
        assertEquals(LocalDate.of(2025, 2, 1), dates.start)
        assertNull(dates.end)
    }

    @Test
    fun invalidArguments() {
        fun error(builder: JsonObjectBuilder.() -> Unit): String = assertThrows<IllegalArgumentException> { parse(builder) }.message!!

        assertTrue("series is required" in error { })
        assertTrue("Unknown metric 'revenue'" in error { put("series", series(metric("revenue"))) })
        assertTrue("mrr-new" in error { put("series", series(metric("revenue"))) }, "the error lists the metrics")
        assertTrue("series[0].window must be one of follow, day" in error { put("series", series(metric("sales") { put("window", "decade") })) })
        assertTrue("average must be one of 1, 3, 12" in error { put("series", series(metric("sales") { put("average", 6) })) })
        assertTrue("Unknown property" in error { put("series", series(metric("sales") { put("colour", "#000000") })) })
        assertTrue("color must be a color" in error { put("series", series(metric("sales") { put("color", "red") })) })
        assertTrue("theme must be one of light, dark" in error { put("series", series(metric("sales"))); put("theme", "blue") })
        assertTrue("at most 8 series" in error { put("series", JsonArray(List(9) { metric("sales") })) })
        assertTrue("detail must be one of" in error { put("series", series(metric("sales"))); put("detail", "quarter") })
        assertTrue("range must be one of" in error { put("series", series(metric("sales"))); put("range", "last-year") })
        assertTrue("either range or startDate" in error { put("series", series(metric("sales"))); put("range", "2024"); put("endDate", "2024-06-30") })
        assertTrue("width must be between" in error { put("series", series(metric("sales"))); put("width", 100) })
        assertTrue("scale must be between" in error { put("series", series(metric("sales"))); put("scale", 4) })
        assertTrue("markers must contain" in error { put("series", series(metric("sales"))); put("markers", "holidays") })
    }

    @Test
    fun windowsAndDetails() {
        assertEquals(ChartWindow.CalendarMonth, ChartRequest.detailWindow(ChartDetail.Month))
        assertEquals(ChartDetail.Month, ChartRequest.lineDetail(ChartWindow.Year, ChartDetail.Month))
        // a window, which is shorter than the steps of the dates, has finer dates
        assertEquals(ChartDetail.Day, ChartRequest.lineDetail(ChartWindow.Day, ChartDetail.Year))
        assertEquals(ChartDetail.Week, ChartRequest.lineDetail(ChartWindow.Week, ChartDetail.Month))
        assertEquals(LocalDate.of(2025, 10, 31), ChartRequest.periodEnd(LocalDate.of(2025, 10, 26), ChartDetail.Month))
        assertEquals(LocalDate.of(2025, 10, 26), ChartRequest.periodEnd(LocalDate.of(2025, 10, 20), ChartDetail.Week))
    }

    @Test
    suspend fun png() {
        val result = tools.result("create_chart", arguments {
            put("series", series(metric("sales"), metric("trials") { put("style", "bars") }))
            put("width", 600)
            put("height", 300)
            put("scale", 2)
            put("markers", buildJsonArray { add("release"); add("events") })
        })
        val json = result.json
        assertEquals(McpTestData.PLUGIN_ID, json["pluginId"]!!.jsonPrimitive.int)
        assertEquals("image/png", json["mimeType"]!!.jsonPrimitive.content)
        assertEquals(1200, json["width"]!!.jsonPrimitive.int)
        assertEquals("Sales before fees and New trials", json["title"]!!.jsonPrimitive.content)
        val chartSeries = json["series"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("sales", "trials"), chartSeries.map { it["metric"]!!.jsonPrimitive.content })
        assertEquals("Sales before fees, calendar month", chartSeries[0]["label"]!!.jsonPrimitive.content)
        assertEquals("USD", chartSeries[0]["unit"]!!.jsonPrimitive.content)
        assertEquals("calendar-month", chartSeries[0]["window"]!!.jsonPrimitive.content)
        assertEquals("bars", chartSeries[1]["style"]!!.jsonPrimitive.content)
        // bars next to a blue line have a different lightness
        assertEquals("#eda100", chartSeries[1]["color"]!!.jsonPrimitive.content)
        // the major releases of the plugin and the custom event
        val markers = json["markers"]!!.jsonArray.map { it.jsonObject["label"]!!.jsonPrimitive.content }
        assertTrue("2.0" in markers || "2.0.0" in markers, markers.toString())
        assertTrue("Launch" in markers)

        val image = result.content.single() as McpContent.Image
        assertEquals("image/png", image.mimeType)
        val decoded = ImageIO.read(ByteArrayInputStream(image.data))
        assertEquals(1200, decoded.width)
        assertEquals(600, decoded.height)
    }

    @Test
    suspend fun svg() {
        val result = tools.result("create_chart", arguments {
            put("series", series(metric("sales"), metric("sales") { put("average", 12) }, metric("licenses")))
            put("format", "svg")
            put("previousYear", true)
            put("range", "2025")
            put("title", "Synthetic chart")
        })
        val svg = (result.content.single() as McpContent.Text).text
        assertTrue(svg.startsWith("<?xml"))
        assertTrue("<title>Synthetic chart</title>" in svg)
        assertTrue("Sales before fees, calendar month, 12-month average" in svg)
        assertTrue("Sales before fees, calendar month, previous year" in svg)
        assertTrue("Active licenses (right axis)" in svg)
        assertEquals("2025-01-01", result.json["startDate"]!!.jsonPrimitive.content)
        assertEquals("2025-12-31", result.json["endDate"]!!.jsonPrimitive.content)
        // the JSON of call has no image
        assertEquals(result.json["series"], tools.call("create_chart", arguments {
            put("series", series(metric("sales"), metric("sales") { put("average", 12) }, metric("licenses")))
            put("format", "svg")
            put("previousYear", true)
            put("range", "2025")
            put("title", "Synthetic chart")
        })["series"])
    }

    @Test
    suspend fun subtitleWithTheDatesOfTheValues() {
        val result = tools.result("create_chart", arguments {
            put("series", series(metric("sales")))
            put("format", "svg")
            put("startDate", "2025-02-01")
        })
        val svg = (result.content.single() as McpContent.Text).text
        assertTrue("2025-02-01 – " in svg, "the start date")
        assertFalse("…" in svg, "the last date instead of an open end")
    }

    @Test
    suspend fun errorsOfTheData() {
        val threeUnits = assertThrows<IllegalArgumentException> {
            tools.result("create_chart", arguments { put("series", series(metric("sales"), metric("trials"), metric("churn-annual"))) })
        }
        assertTrue("at most two units" in threeUnits.message!!, threeUnits.message)

        // the test provider has no summary of all plugins
        val summary = assertThrows<IllegalArgumentException> {
            tools.result("create_chart", buildJsonObject { put("series", series(metric("sales"))) })
        }
        assertTrue("pluginId is required" in summary.message!!)

        val markers = assertThrows<IllegalArgumentException> {
            tools.result("create_chart", buildJsonObject { put("series", series(metric("sales"))); put("markers", "price") })
        }
        assertTrue("need a pluginId" in markers.message!!)
    }
}
