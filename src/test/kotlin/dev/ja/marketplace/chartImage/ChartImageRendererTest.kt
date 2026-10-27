/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.chartImage

import dev.ja.marketplace.data.chart.ChartDetail
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.time.LocalDate
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

class ChartImageRendererTest {
    private val months = (1..12).map { LocalDate.of(2025, it, 1).withDayOfMonth(LocalDate.of(2025, it, 1).lengthOfMonth()) }

    private fun chart(vararg series: ChartImageSeries, markers: List<ChartMarker> = emptyList(), extremes: Set<ChartExtreme> = emptySet(), logScale: Boolean = false): ChartImage {
        return ChartImage(
            "Synthetic sales", "Test plugin", series.toList(), markers, ChartDetail.Month, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), extremes,
            logScale = logScale,
        )
    }

    private val sales = ChartImageSeries(
        "Sales before fees, calendar month",
        "EUR",
        ChartColor.SeriesColors[0],
        months.mapIndexed { index, date -> ChartPoint(date, 100.0 + index * 10, isIncomplete = index == 11, isPartial = index == 0) },
    )

    private val trials = ChartImageSeries(
        "New trials, calendar month",
        "count",
        ChartColor.SeriesColors[1],
        months.mapIndexed { index, date -> ChartPoint(date, (index % 4).toDouble(), isIncomplete = index == 11) },
        style = ChartStyle.Bars,
    )

    private val average = ChartImageSeries(
        "Sales before fees, calendar month, 3-month average",
        "EUR",
        ChartColor.SeriesColors[2],
        months.drop(2).map { ChartPoint(it, 110.0) },
        kind = ChartSeriesKind.Average,
        averageMonths = 3,
    )

    @Test
    fun svgContainsTheSeries() {
        val svg = ChartImageRenderer.svg(
            chart(sales, trials, average, markers = listOf(ChartMarker(LocalDate.of(2025, 6, 1), "2.0", ChartMarkerKind.Release)), extremes = setOf(ChartExtreme.Max)),
            800, 400,
        )
        // well-formed XML
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(svg.toByteArray()))
        assertEquals("svg", document.documentElement.tagName)
        assertEquals("800", document.documentElement.getAttribute("width"))

        assertTrue("Synthetic sales" in svg)
        assertTrue("Sales before fees, calendar month" in svg)
        assertTrue("New trials, calendar month (right axis)" in svg, "the second unit is on the right axis")
        assertTrue("Amount (EUR)" in svg)
        assertTrue("Count" in svg)
        assertEquals(1, Regex(">Incomplete values<").findAll(svg).count(), "a single entry for the incomplete lines and bars")
        assertTrue("Product version" in svg)
        assertTrue(">2.0<" in svg, "the label of the marker")
        assertTrue(">200 EUR<" in svg, "the label of the maximum, without the incomplete value")
        assertTrue("stroke=\"#2a78d6\"" in svg)
        assertTrue("fill=\"#eb6834\"" in svg, "the bars")
        // the dashes of a 3-month average next to values, the dots of incomplete values
        assertTrue("stroke-dasharray=\"6 3\"" in svg)
        assertTrue("stroke-dasharray=\"0 5\"" in svg)
        assertTrue("Feb 2025" in svg, "a tick of every second month")
    }

    @Test
    fun pngHasTheRequestedSize() {
        val png = ChartImageRenderer.png(chart(sales, trials), 640, 320, scale = 2.0)
        val image = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(1280, image.width)
        assertEquals(640, image.height)
        // the background is white, the first series is drawn in its color
        assertEquals(0xffffff, image.getRGB(2, 2) and 0xffffff)
        val colors = (0 until image.width step 2).flatMap { x -> (0 until image.height step 2).map { y -> image.getRGB(x, y) and 0xffffff } }.toSet()
        assertTrue(0x2a78d6 in colors)
        assertTrue(0xeb6834 in colors)
    }

    @Test
    fun tooSmallForTheLegend() {
        val many = (0 until 8).map { ChartImageSeries("A long label of the series number $it", "count", ChartColor.SeriesColors[it], listOf(ChartPoint(months[0], 1.0))) }
        assertThrows<IllegalArgumentException> { ChartImageRenderer.svg(chart(*many.toTypedArray()), 320, 200) }
    }

    @Test
    fun atMostTwoUnits() {
        val ratio = ChartImageSeries("Quick ratio", "ratio", ChartColor.SeriesColors[3], listOf(ChartPoint(months[0], 1.0)))
        assertThrows<IllegalArgumentException> { chart(sales, trials, ratio) }
    }

    @Test
    fun incompleteColorLikeTheWebCharts() {
        // the values of incompleteColor of model.js
        assertEquals("#76a2d6", ChartColor.parse("#2a78d6").incomplete().hex)
        assertEquals("#e49b7e", ChartColor.parse("#eb6834").incomplete().hex)
        assertEquals("#18dc18", ChartColor.parse("#008300").incomplete().hex)
        // the values of incompleteColor of model.js in the dark mode
        assertEquals("#244b7a", ChartColor.parse("#2a78d6").incomplete(dark = true).hex)
        assertEquals("#93401f", ChartColor.parse("#eb6834").incomplete(dark = true).hex)
        assertEquals("#5f5e66", ChartColor.parse("#008300").incomplete(dark = true).hex)
        assertThrows<IllegalArgumentException> { ChartColor.parse("blue") }
    }

    @Test
    fun darkTheme() {
        val light = ChartImageRenderer.svg(chart(sales, trials), 800, 400)
        val dark = ChartImageRenderer.svg(
            ChartImage(
                "Synthetic sales", "Test plugin", listOf(sales, trials), listOf(ChartMarker(LocalDate.of(2025, 6, 1), "2.0", ChartMarkerKind.Release)),
                ChartDetail.Month, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), theme = ChartTheme.Dark,
            ),
            800, 400,
        )
        // the surface, the dark colors of the series and of the vertical lines, like the dark mode of the web charts
        assertTrue("#1b1b21" in dark && "#1b1b21" !in light)
        assertTrue("#3987e5" in dark && "#2a78d6" !in dark, "the dark color of the first series")
        assertTrue("#d95926" in dark, "the dark color of the bars")
        assertTrue("#9a99a2" in dark, "the dark color of the release")
        assertTrue("#ffffff" in light && "#ffffff" !in dark)
        assertEquals(ChartColor.parse("#3987e5"), ChartTheme.Dark.seriesColor(ChartColor.SeriesColors[0]))
        assertEquals(ChartColor.parse("#123456"), ChartTheme.Dark.seriesColor(ChartColor.parse("#123456")), "other colors are unchanged")
        assertEquals(ChartColor.SeriesColors[0], ChartTheme.Light.seriesColor(ChartColor.SeriesColors[0]))
    }

    @Test
    fun nextColorLikeTheWebCharts() {
        val (blue, orange, green, yellow) = ChartColor.SeriesColors
        // the values of nextColor and colorDistance of model.js
        assertEquals(orange, ChartColor.next(setOf(blue), listOf(blue to ChartStyle.Lines), ChartStyle.Lines))
        assertEquals(yellow, ChartColor.next(setOf(blue), listOf(blue to ChartStyle.Bars), ChartStyle.Lines))
        assertEquals(green, ChartColor.next(setOf(blue, orange), emptyList(), ChartStyle.Lines))
        assertNull(ChartColor.next(ChartColor.SeriesColors.toSet(), emptyList(), ChartStyle.Lines))
        assertEquals(0.0, ChartColor.distance(blue, blue))
        assertTrue(ChartColor.distance(blue, green, lineOverBar = true) < ChartColor.MIN_DISTANCE)
    }

    @Test
    fun logRangeLikeTheWebCharts() {
        // the values of logRange of model.js
        assertEquals(AxisRange(10.0, 1000.0, 0.0, log = true), ChartAxes.logRange(12.0, 830.0))
        assertEquals(AxisRange(100.0, 1000.0, 0.0, log = true), ChartAxes.logRange(100.0, 100.0))
        assertEquals(AxisRange(0.1, 10.0, 0.0, log = true), ChartAxes.logRange(0.5, 3.0))
        assertEquals(AxisRange(1.0, 10.0, 0.0, log = true), ChartAxes.logRange(null, null))

        assertEquals(listOf(10.0, 20.0, 30.0, 50.0, 70.0, 100.0, 200.0, 300.0, 500.0, 700.0, 1000.0), ChartAxes.logRange(12.0, 830.0).ticks)
        assertEquals(listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0), ChartAxes.logRange(1.0, 1000.0).ticks)
        // a wide range only has the powers of ten
        assertEquals(listOf(1.0, 10.0, 100.0, 1000.0, 1e4, 1e5, 1e6), ChartAxes.logRange(1.0, 1e6).ticks)
    }

    @Test
    fun logScale() {
        // the trials of every 4th month are 0, they have no position on the logarithmic axis
        val svg = ChartImageRenderer.svg(chart(sales, trials, average, extremes = setOf(ChartExtreme.Min, ChartExtreme.Max), logScale = true), 800, 400)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(svg.toByteArray()))
        assertEquals("svg", document.documentElement.tagName)
        assertFalse("NaN" in svg || "Infinity" in svg, "values of 0 have no position")
        // the ticks of the amounts from 100 to 1,000 and of the trials from 1 to 10
        for (label in listOf(">100<", ">200<", ">500<", ">1K<", ">1<", ">5<", ">10<")) {
            assertTrue(label in svg, label)
        }
        // the minimum of the trials is the lowest positive value
        assertTrue(">1<" in svg)
        ImageIO.read(ByteArrayInputStream(ChartImageRenderer.png(chart(sales, trials, logScale = true), 600, 300)))
    }

    @Test
    fun alignedRangesLikeTheWebCharts() {
        // the values of alignedRanges of model.js
        assertEquals(
            listOf(AxisRange(-250.0, 1250.0, 250.0), AxisRange(-10.0, 50.0, 10.0)),
            ChartAxes.alignedRanges(listOf(0.0 to 1234.0, -5.0 to 40.0), 5),
        )
        assertEquals(250.0, ChartAxes.niceStep(1234.0, 5))
        assertEquals(listOf(0.0, 0.1, 0.2, 0.3), AxisRange(0.0, 0.3, 0.1).ticks)
    }

    @Test
    fun numbers() {
        assertEquals("1.2K", ChartAxes.compact(1234.0))
        assertEquals("2M", ChartAxes.compact(2_000_000.0))
        assertEquals("0.25", ChartAxes.compact(0.25))
        assertEquals("1,234.5 EUR", ChartAxes.value(1234.5, "EUR"))
        assertEquals("12.5 %", ChartAxes.value(12.5, "%"))
        // the decimals of the metric, e.g. the average of the active paid licenses
        assertEquals("1,235", ChartAxes.value(1234.5, "count", 0))
        assertEquals("0.4", ChartAxes.value(0.44, "count", 1))
        assertEquals("Amount (USD)", ChartAxes.unitTitle("USD"))
    }

    @Test
    fun dateTicks() {
        val years = ChartAxes.dateTicks(LocalDate.of(2022, 1, 1), LocalDate.of(2025, 12, 31), ChartDetail.Year, LocalDate.of(2025, 10, 26), 10)
        assertEquals(listOf("2022", "2023", "2024", "2025"), years.map(DateTick::label))
        // the tick of the current year is at its value
        assertEquals(LocalDate.of(2025, 10, 26), years.last().date)

        val months = ChartAxes.dateTicks(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30), ChartDetail.Month, null, 10)
        assertEquals(listOf("Jan 2025", "Feb", "Mar", "Apr", "May", "Jun"), months.map(DateTick::label))
        assertEquals(LocalDate.of(2025, 2, 28), months[1].date)

        val quarters = ChartAxes.dateTicks(LocalDate.of(2023, 1, 1), LocalDate.of(2025, 12, 31), ChartDetail.Month, null, 12)
        assertTrue(quarters.size <= 12)
        assertTrue(quarters.all { it.date.monthValue % 3 == 0 })

        val days = ChartAxes.dateTicks(LocalDate.of(2025, 10, 20), LocalDate.of(2025, 10, 26), ChartDetail.Day, null, 10)
        assertEquals("20 Oct", days.first().label)
        assertEquals(7, days.size)

        // a month at the level Month has the ticks of its days
        val thisMonth = ChartAxes.dateTicks(LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 26), ChartDetail.Month, null, 10)
        assertTrue(thisMonth.size >= 2)
        assertTrue(thisMonth.all { it.label.endsWith("Oct") })
    }
}
