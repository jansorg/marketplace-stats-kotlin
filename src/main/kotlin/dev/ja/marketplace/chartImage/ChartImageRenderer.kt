/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.chartImage

import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders a [ChartImage] as SVG or PNG. Both formats are drawn by the same code with a [ChartCanvas].
 * The styles are those of the web charts (`CHART_STYLE` of `lineChart.js`): lines of 2 pixels, points with a ring,
 * dotted lines and hollow points of incomplete values, dashed moving averages, a faint line of the previous year,
 * thin bars with a rounded end, striped incomplete bars and outlined partial bars.
 */
object ChartImageRenderer {
    const val MIN_WIDTH = 320
    const val MAX_WIDTH = 3000
    const val MIN_HEIGHT = 200
    const val MAX_HEIGHT = 2000

    private const val PADDING = 16.0
    private const val TITLE_SIZE = 15.0
    private const val TEXT_SIZE = 12.0
    private const val MARKER_LABEL_SIZE = 11.0
    private const val ROW_HEIGHT = 20.0
    private const val SWATCH_WIDTH = 14.0
    private const val SWATCH_GAP = 6.0
    private const val ENTRY_GAP = 20.0

    private const val LINE_WIDTH = 2.0
    private const val OVERLAY_WIDTH = 1.5
    private const val MARKER_WIDTH = 2.0
    private const val POINT_RADIUS = 3.0
    private const val POINT_RING = 2.0
    private const val MAX_POINTS_SHOWN = 120
    private const val MAX_BAR_WIDTH = 24.0
    private const val BAR_GAP = 2.0
    private const val BAR_RADIUS = 4.0
    private const val PARTIAL_BAR_ALPHA = 102
    private const val INCOMPLETE_DOT_SPACING = 5.0
    private const val INCOMPLETE_STRIPE_SPACING = 5.0

    /** The alpha of the previous year, appended to the color of the series in `CHART_STYLE.overlays`. */
    private const val PREVIOUS_YEAR_ALPHA = 0x59

    private val averageDashes = mapOf(1 to listOf(3.0, 3.0), 3 to listOf(6.0, 3.0), 12 to listOf(14.0, 4.0))
    private val dottedDash = listOf(0.0, INCOMPLETE_DOT_SPACING)

    fun svg(chart: ChartImage, width: Int, height: Int): String {
        val canvas = SvgChartCanvas(width, height)
        draw(chart, canvas, width.toDouble(), height.toDouble())
        return canvas.toSvg(chart.title)
    }

    /**
     * @param scale The pixel density, e.g. 2 for an image with twice the width and height in pixels, which shows the
     *  same chart with sharper lines and texts.
     */
    fun png(chart: ChartImage, width: Int, height: Int, scale: Double = 1.0): ByteArray {
        val image = BufferedImage((width * scale).roundToInt(), (height * scale).roundToInt(), BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.scale(scale, scale)
            draw(chart, Graphics2DChartCanvas(graphics), width.toDouble(), height.toDouble())
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)) { "No PNG writer available" }
            output.toByteArray()
        }
    }

    private class LegendEntry(val label: String, val color: ChartColor, val shape: LegendShape, val dash: List<Double>? = null) {
        val swatchWidth: Double = if (shape == LegendShape.DottedAndStripes) 2 * SWATCH_WIDTH + SWATCH_GAP else SWATCH_WIDTH
        val width: Double = swatchWidth + SWATCH_GAP + ChartFonts.width(label, TEXT_SIZE)
    }

    /** [DottedAndStripes] is the single legend entry of the incomplete values of lines and bars. */
    private enum class LegendShape { Line, Dotted, Box, Stripes, DottedAndStripes, Marker }

    /** The pixels of the plot and the scales of its axes. */
    private class Plot(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
        val start: Double,
        val end: Double,
        val ranges: Map<String, AxisRange>,
    ) {
        val width: Double get() = right - left
        val height: Double get() = bottom - top
        val bounds: Rectangle2D get() = Rectangle2D.Double(left, top, width, height)

        fun x(date: LocalDate): Double = left + (date.toEpochDay() - start) / (end - start) * width

        fun y(value: Double, unit: String): Double {
            val range = ranges.getValue(unit)
            if (range.log) {
                return bottom - (log10(value) - log10(range.min)) / (log10(range.max) - log10(range.min)) * height
            }
            return bottom - (value - range.min) / (range.max - range.min) * height
        }

        /** @return If the value has a position on the axis of the unit: a logarithmic axis has no 0 and no negative values. */
        fun isDrawn(value: Double, unit: String): Boolean = !ranges.getValue(unit).log || value > 0

        /** @return The position of the start of a bar: 0, or the bottom of a logarithmic axis. */
        fun barBase(unit: String): Double = if (ranges.getValue(unit).log) bottom else y(0.0, unit)
    }

    internal fun draw(image: ChartImage, canvas: ChartCanvas, width: Double, height: Double) {
        // the series have the colors of the theme
        val chart = image.withThemeColors()
        val theme = chart.theme
        canvas.fill(Rectangle2D.Double(0.0, 0.0, width, height), theme.surface)

        var top = PADDING
        canvas.text(chart.title, PADDING, top + TITLE_SIZE, TITLE_SIZE, theme.text, bold = true)
        top += TITLE_SIZE + 8
        if (!chart.subtitle.isNullOrBlank()) {
            canvas.text(chart.subtitle, PADDING, top + TEXT_SIZE, TEXT_SIZE, theme.axis)
            top += TEXT_SIZE + 8
        }

        val visible = chart.series.map { series -> series.points.filter { it.date >= chart.start && it.date <= chart.end } }
        val markers = chart.markers.filter { it.date >= chart.start && it.date <= chart.end }.sortedBy { it.date }

        val legendRows = legendRows(legendEntries(chart, visible, markers), width - 2 * PADDING)
        val legendTop = height - PADDING - legendRows.size * ROW_HEIGHT
        val plotBottom = legendTop - 12 - TEXT_SIZE - 6
        val plotTop = top + if (markers.isNotEmpty()) MARKER_LABEL_SIZE + 10 else 6.0
        require(plotBottom - plotTop >= 40) { "The chart is too small for its legend, use a larger height" }

        // the y-axes have their ticks at the same heights, an axis without values has the range of 0 to 1
        val extents = chart.units.map { unit ->
            val values = chart.series.indices.filter { chart.series[it].unit == unit }.flatMap { index -> visible[index].map(ChartPoint::value) }
            (values.minOrNull() ?: 0.0) to (values.maxOrNull()?.takeIf { it != 0.0 || (values.minOrNull() ?: 0.0) != 0.0 } ?: 1.0)
        }
        val tickCount = max(2, ((plotBottom - plotTop) / 60).roundToInt())
        val ranges = when {
            // logarithmic axes have their own ranges and ticks around the positive values
            chart.logScale -> chart.units.associateWith { unit ->
                val values = chart.series.indices.filter { chart.series[it].unit == unit }.flatMap { index -> visible[index].map(ChartPoint::value) }.filter { it > 0 }
                ChartAxes.logRange(values.minOrNull(), values.maxOrNull())
            }

            else -> chart.units.zip(ChartAxes.alignedRanges(extents.ifEmpty { listOf(0.0 to 1.0) }, tickCount)).toMap()
        }
        val tickLabels = chart.units.associateWith { unit ->
            ranges.getValue(unit).ticks.map { if (chart.hideAxisValues) "" else ChartAxes.compact(it) }
        }
        val labelWidths = tickLabels.mapValues { (_, labels) -> labels.maxOfOrNull { ChartFonts.width(it, TEXT_SIZE) } ?: 0.0 }

        val plotLeft = PADDING + TEXT_SIZE + 10 + (labelWidths[chart.units.firstOrNull()] ?: 0.0) + 6
        val plotRight = when (chart.units.size) {
            2 -> width - PADDING - TEXT_SIZE - 10 - labelWidths.getValue(chart.units[1]) - 6
            else -> width - PADDING - 12
        }
        require(plotRight - plotLeft >= 60) { "The chart is too narrow, use a larger width" }

        // the bars at the first and the last date aren't cut off, they have half of the distance of the dates
        val barDays = chart.series.indices.filter { chart.series[it].isBar }.flatMap { visible[it] }.map { it.date.toEpochDay() }.distinct().sorted()
        val barPadding = (barDays.zipWithNext { a, b -> b - a }.minOrNull()?.toDouble() ?: 1.0) / 2
        val start = chart.start.toEpochDay().toDouble() - if (barDays.firstOrNull() == chart.start.toEpochDay()) barPadding else 0.0
        val end = chart.end.toEpochDay().toDouble() + if (barDays.lastOrNull() == chart.end.toEpochDay()) barPadding else 0.0
        val plot = Plot(plotLeft, plotTop, plotRight, plotBottom, if (start == end) start - 0.5 else start, if (start == end) end + 0.5 else end, ranges)

        drawAxes(chart, canvas, plot, tickLabels, width)
        drawMarkers(canvas, plot, markers, width, theme)

        canvas.clip(plot.bounds) {
            drawBars(chart, canvas, plot, visible)
            val hasValues = chart.series.any { it.kind == ChartSeriesKind.Values }
            chart.series.forEachIndexed { index, series ->
                when {
                    series.isBar -> Unit
                    series.kind == ChartSeriesKind.PreviousYear -> {
                        canvas.stroke(linePath(plot, series.unit, visible[index]), series.color.withAlpha(PREVIOUS_YEAR_ALPHA), OVERLAY_WIDTH)
                    }

                    series.kind == ChartSeriesKind.Values -> drawLine(canvas, plot, series, visible[index], null, theme)
                }
            }
            // the moving averages are above the values, e.g. above bars
            chart.series.forEachIndexed { index, series ->
                if (series.kind == ChartSeriesKind.Average) {
                    drawLine(canvas, plot, series, visible[index], if (hasValues) averageDashes[series.averageMonths] else null, theme)
                }
            }
        }

        // points at the edges of the plot aren't cut off
        val margin = POINT_RADIUS + POINT_RING
        canvas.clip(Rectangle2D.Double(plot.left - margin, plot.top - margin, plot.width + 2 * margin, plot.height + 2 * margin)) {
            chart.series.forEachIndexed { index, series ->
                if (series.kind == ChartSeriesKind.Values && !series.isBar && visible[index].size <= MAX_POINTS_SHOWN) {
                    drawPoints(canvas, plot, series, visible[index], theme)
                }
            }
        }

        if (chart.extremes.isNotEmpty()) {
            drawExtremes(chart, canvas, plot, visible)
        }
        drawLegend(canvas, legendRows, legendTop, theme)
    }

    private fun drawAxes(chart: ChartImage, canvas: ChartCanvas, plot: Plot, tickLabels: Map<String, List<String>>, width: Double) {
        // the grid lines of the first axis, the ticks of the second axis are at the same heights
        chart.units.forEachIndexed { axis, unit ->
            val range = plot.ranges.getValue(unit)
            range.ticks.forEachIndexed { index, tick ->
                val y = plot.y(tick, unit)
                if (axis == 0) {
                    canvas.stroke(Line2D.Double(plot.left, y, plot.right, y), chart.theme.grid, 1.0)
                }
                val label = tickLabels.getValue(unit)[index]
                if (label.isNotEmpty()) {
                    when (axis) {
                        0 -> canvas.text(label, plot.left - 6, y + 4, TEXT_SIZE, chart.theme.axis, TextAnchor.End)
                        else -> canvas.text(label, plot.right + 6, y + 4, TEXT_SIZE, chart.theme.axis)
                    }
                }
            }
            val title = ChartAxes.unitTitle(unit)
            val centerY = (plot.top + plot.bottom) / 2
            when (axis) {
                0 -> canvas.text(title, PADDING + TEXT_SIZE, centerY, TEXT_SIZE, chart.theme.axis, TextAnchor.Middle, rotated = true)
                else -> canvas.text(title, width - PADDING, centerY, TEXT_SIZE, chart.theme.axis, TextAnchor.Middle, rotated = true)
            }
        }
        if (chart.units.isEmpty()) {
            canvas.stroke(Line2D.Double(plot.left, plot.bottom, plot.right, plot.bottom), chart.theme.grid, 1.0)
        }

        val lastValue = chart.series.filter { it.kind == ChartSeriesKind.Values }.flatMap(ChartImageSeries::points).maxOfOrNull(ChartPoint::date)
        val maxTicks = max(2, (plot.width / 90).toInt())
        val ticks = ChartAxes.dateTicks(chart.start, chart.end, chart.detail, lastValue, maxTicks)
        var lastLabelEnd = Double.NEGATIVE_INFINITY
        for (tick in ticks) {
            val x = plot.x(tick.date)
            canvas.stroke(Line2D.Double(x, plot.top, x, plot.bottom), chart.theme.grid, 1.0)
            val labelWidth = ChartFonts.width(tick.label, TEXT_SIZE)
            val center = x.coerceIn(labelWidth / 2 + 2, width - labelWidth / 2 - 2)
            if (center - labelWidth / 2 > lastLabelEnd + 8) {
                canvas.text(tick.label, center, plot.bottom + 6 + TEXT_SIZE, TEXT_SIZE, chart.theme.axis, TextAnchor.Middle)
                lastLabelEnd = center + labelWidth / 2
            }
        }
    }

    private fun drawMarkers(canvas: ChartCanvas, plot: Plot, markers: List<ChartMarker>, width: Double, theme: ChartTheme) {
        var lastLabelEnd = Double.NEGATIVE_INFINITY
        for (marker in markers) {
            val x = plot.x(marker.date)
            canvas.stroke(Line2D.Double(x, plot.top, x, plot.bottom), theme.markerColor(marker.kind), MARKER_WIDTH, marker.kind.dash)
            // labels, which would overlap the previous label, are left out
            val labelWidth = ChartFonts.width(marker.label, MARKER_LABEL_SIZE)
            val center = x.coerceIn(labelWidth / 2, width - labelWidth / 2)
            if (center - labelWidth / 2 > lastLabelEnd + 6) {
                canvas.text(marker.label, center, plot.top - 4, MARKER_LABEL_SIZE, theme.axis, TextAnchor.Middle)
                lastLabelEnd = center + labelWidth / 2
            }
        }
    }

    /** @return The line through the points, a value without a position, e.g. 0 on a logarithmic axis, is a gap. */
    private fun linePath(plot: Plot, unit: String, points: List<ChartPoint>): Path2D {
        val path = Path2D.Double()
        var isConnected = false
        for (point in points) {
            if (!plot.isDrawn(point.value, unit)) {
                isConnected = false
                continue
            }
            val x = plot.x(point.date)
            val y = plot.y(point.value, unit)
            if (isConnected) path.lineTo(x, y) else path.moveTo(x, y)
            isConnected = true
        }
        return path
    }

    /**
     * Draws the line of the complete values and the dotted line to the incomplete values, which starts at the last
     * complete value.
     */
    private fun drawLine(canvas: ChartCanvas, plot: Plot, series: ChartImageSeries, points: List<ChartPoint>, dash: List<Double>?, theme: ChartTheme) {
        val firstIncomplete = points.indexOfFirst(ChartPoint::isIncomplete).takeIf { it >= 0 } ?: points.size
        val complete = points.subList(0, firstIncomplete)
        if (complete.size > 1) {
            canvas.stroke(linePath(plot, series.unit, complete), series.color, LINE_WIDTH, dash)
        }
        val incomplete = points.subList(max(0, firstIncomplete - 1), points.size)
        if (firstIncomplete < points.size && incomplete.size > 1) {
            canvas.stroke(linePath(plot, series.unit, incomplete), theme.incomplete(series.color), LINE_WIDTH, dottedDash, roundCap = true)
        }
    }

    private fun drawPoints(canvas: ChartCanvas, plot: Plot, series: ChartImageSeries, points: List<ChartPoint>, theme: ChartTheme) {
        for (point in points.filter { plot.isDrawn(it.value, series.unit) }) {
            val x = plot.x(point.date)
            val y = plot.y(point.value, series.unit)
            canvas.fill(circle(x, y, POINT_RADIUS + POINT_RING), theme.surface)
            when {
                point.isIncomplete -> canvas.stroke(circle(x, y, POINT_RADIUS - 0.5), theme.incomplete(series.color), 1.5)
                point.isPartial -> canvas.stroke(circle(x, y, POINT_RADIUS - 0.5), series.color, 1.5)
                else -> canvas.fill(circle(x, y, POINT_RADIUS), series.color)
            }
        }
    }

    private fun circle(x: Double, y: Double, radius: Double) = Ellipse2D.Double(x - radius, y - radius, 2 * radius, 2 * radius)

    /**
     * Draws the bars of the bar series next to each other at each date, like `barLayout` of `model.js`.
     */
    private fun drawBars(chart: ChartImage, canvas: ChartCanvas, plot: Plot, visible: List<List<ChartPoint>>) {
        val barIndices = chart.series.indices.filter { chart.series[it].isBar }
        if (barIndices.isEmpty()) {
            return
        }
        val positions = barIndices.flatMap { visible[it] }.map { plot.x(it.date) }.distinct().sorted()
        val slotWidth = positions.zipWithNext { a, b -> b - a }.minOrNull() ?: (plot.width / 4)
        val count = barIndices.size
        val barWidth = max(1.0, min(MAX_BAR_WIDTH, (slotWidth * 0.7 - BAR_GAP * (count - 1)) / count))
        val totalWidth = barWidth * count + BAR_GAP * (count - 1)

        barIndices.forEachIndexed { position, index ->
            val series = chart.series[index]
            val offset = -totalWidth / 2 + position * (barWidth + BAR_GAP)
            for (point in visible[index].filter { plot.isDrawn(it.value, series.unit) }) {
                val x = plot.x(point.date) + offset
                val base = plot.barBase(series.unit)
                val end = plot.y(point.value, series.unit)
                if (abs(end - base) < 0.5) {
                    continue
                }
                val shape = barShape(x, barWidth, base, end)
                when {
                    point.isIncomplete -> {
                        val color = chart.theme.incomplete(series.color)
                        canvas.clip(shape.bounds2D) {
                            var stripe = -abs(end - base)
                            while (stripe < barWidth) {
                                canvas.stroke(Line2D.Double(x + stripe, max(base, end), x + stripe + abs(end - base), min(base, end)), color, 1.5)
                                stripe += INCOMPLETE_STRIPE_SPACING
                            }
                        }
                        canvas.stroke(shape, color, 1.0)
                    }

                    point.isPartial -> {
                        canvas.fill(shape, series.color.withAlpha(PARTIAL_BAR_ALPHA))
                        canvas.stroke(shape, series.color, 1.0)
                    }

                    else -> canvas.fill(shape, series.color)
                }
            }
        }
    }

    /** @return A bar from the base to the end with rounded corners at the end, e.g. at the top of a positive value. */
    private fun barShape(x: Double, width: Double, base: Double, end: Double): Path2D {
        val radius = min(BAR_RADIUS, min(width / 2, abs(end - base)))
        val direction = if (end < base) 1.0 else -1.0
        return Path2D.Double().apply {
            moveTo(x, base)
            lineTo(x, end + direction * radius)
            quadTo(x, end, x + radius, end)
            lineTo(x + width - radius, end)
            quadTo(x + width, end, x + width, end + direction * radius)
            lineTo(x + width, base)
            closePath()
        }
    }

    /**
     * Marks the lowest and the highest complete value of each series of values with a ring and its value.
     */
    private fun drawExtremes(chart: ChartImage, canvas: ChartCanvas, plot: Plot, visible: List<List<ChartPoint>>) {
        chart.series.forEachIndexed { index, series ->
            if (series.kind != ChartSeriesKind.Values) {
                return@forEachIndexed
            }
            val complete = visible[index].filter { !it.isIncomplete && plot.isDrawn(it.value, series.unit) }
            val min = complete.minByOrNull(ChartPoint::value) ?: return@forEachIndexed
            val max = complete.maxBy(ChartPoint::value)
            if (nearlyEqual(min.value, max.value)) {
                return@forEachIndexed
            }
            for (extreme in chart.extremes) {
                val point = if (extreme == ChartExtreme.Min) min else max
                val x = plot.x(point.date)
                val y = plot.y(point.value, series.unit)
                // bars have their labels above the bar, lines a ring around the point
                if (!series.isBar) {
                    canvas.stroke(circle(x, y, POINT_RADIUS + 2.5), series.color, 2.0)
                }
                if (!chart.hideAxisValues) {
                    val label = ChartAxes.value(point.value, series.unit, series.fractionDigits)
                    val size = TEXT_SIZE + 1
                    val labelWidth = ChartFonts.width(label, size, bold = true)
                    val center = x.coerceIn(plot.left + labelWidth / 2 + 3, plot.right - labelWidth / 2 - 3)
                    val above = extreme == ChartExtreme.Max || series.isBar
                    val baseline = (if (above) y - 10 else y + 10 + size).coerceIn(plot.top + size + 2, plot.bottom - 4)
                    // a background keeps the label readable on lines, bars and the grid
                    canvas.fill(RoundRectangle2D.Double(center - labelWidth / 2 - 3, baseline - size - 1, labelWidth + 6, size + 5, 4.0, 4.0), chart.theme.surface.withAlpha(217))
                    canvas.text(label, center, baseline, size, series.color, TextAnchor.Middle, bold = true)
                }
            }
        }
    }

    private fun legendEntries(chart: ChartImage, visible: List<List<ChartPoint>>, markers: List<ChartMarker>): List<LegendEntry> {
        val hasValues = chart.series.any { it.kind == ChartSeriesKind.Values }
        val entries = chart.series.map { series ->
            val label = if (chart.units.size > 1 && series.unit == chart.units[1]) "${series.label} (right axis)" else series.label
            when {
                series.isBar -> LegendEntry(label, series.color, LegendShape.Box)
                series.kind == ChartSeriesKind.PreviousYear -> LegendEntry(label, series.color.withAlpha(PREVIOUS_YEAR_ALPHA), LegendShape.Line)
                series.kind == ChartSeriesKind.Average && hasValues -> LegendEntry(label, series.color, LegendShape.Line, averageDashes[series.averageMonths])
                else -> LegendEntry(label, series.color, LegendShape.Line)
            }
        }.toMutableList()

        val incomplete = chart.series.indices.filter { index -> chart.series[index].kind != ChartSeriesKind.PreviousYear && visible[index].any(ChartPoint::isIncomplete) }
        val incompleteLines = incomplete.any { !chart.series[it].isBar }
        val incompleteBars = incomplete.any { chart.series[it].isBar }
        if (incompleteLines || incompleteBars) {
            val shape = when {
                incompleteLines && incompleteBars -> LegendShape.DottedAndStripes
                incompleteBars -> LegendShape.Stripes
                else -> LegendShape.Dotted
            }
            entries += LegendEntry("Incomplete values", chart.theme.incompleteGrey, shape)
        }
        markers.map(ChartMarker::kind).distinct().sorted().forEach { kind ->
            entries += LegendEntry(kind.label, chart.theme.markerColor(kind), LegendShape.Marker, kind.dash)
        }
        return entries
    }

    private fun legendRows(entries: List<LegendEntry>, maxWidth: Double): List<List<LegendEntry>> {
        val rows = mutableListOf<MutableList<LegendEntry>>()
        var rowWidth = 0.0
        for (entry in entries) {
            val row = rows.lastOrNull()
            if (row == null || rowWidth + ENTRY_GAP + entry.width > maxWidth) {
                rows += mutableListOf(entry)
                rowWidth = entry.width
            } else {
                row += entry
                rowWidth += ENTRY_GAP + entry.width
            }
        }
        return rows
    }

    private fun drawLegend(canvas: ChartCanvas, rows: List<List<LegendEntry>>, top: Double, theme: ChartTheme) {
        rows.forEachIndexed { rowIndex, row ->
            val centerY = top + rowIndex * ROW_HEIGHT + ROW_HEIGHT / 2
            var x = PADDING
            for (entry in row) {
                drawSwatch(canvas, entry, x, centerY)
                canvas.text(entry.label, x + entry.swatchWidth + SWATCH_GAP, centerY + 4, TEXT_SIZE, theme.text)
                x += entry.width + ENTRY_GAP
            }
        }
    }

    private fun drawSwatch(canvas: ChartCanvas, entry: LegendEntry, x: Double, centerY: Double) {
        val line = Line2D.Double(x, centerY, x + SWATCH_WIDTH, centerY)
        when (entry.shape) {
            LegendShape.Line -> canvas.stroke(line, entry.color, LINE_WIDTH, entry.dash)
            LegendShape.Dotted -> drawDottedSwatch(canvas, entry.color, x, centerY)
            LegendShape.Marker -> canvas.stroke(Line2D.Double(x + SWATCH_WIDTH / 2, centerY - 7, x + SWATCH_WIDTH / 2, centerY + 7), entry.color, MARKER_WIDTH, entry.dash)
            LegendShape.Box -> canvas.fill(Rectangle2D.Double(x + 2, centerY - 5, 10.0, 10.0), entry.color)
            LegendShape.Stripes -> drawStripesSwatch(canvas, entry.color, x, centerY)
            LegendShape.DottedAndStripes -> {
                drawDottedSwatch(canvas, entry.color, x, centerY)
                drawStripesSwatch(canvas, entry.color, x + SWATCH_WIDTH + SWATCH_GAP, centerY)
            }
        }
    }

    private fun drawDottedSwatch(canvas: ChartCanvas, color: ChartColor, x: Double, centerY: Double) {
        canvas.stroke(Line2D.Double(x, centerY, x + SWATCH_WIDTH, centerY), color, LINE_WIDTH, listOf(0.0, 4.0), roundCap = true)
    }

    private fun drawStripesSwatch(canvas: ChartCanvas, color: ChartColor, x: Double, centerY: Double) {
        val box = Rectangle2D.Double(x + 2.5, centerY - 4.5, 9.0, 9.0)
        canvas.clip(box) {
            var offset = -10.0
            while (offset <= 10) {
                canvas.stroke(Line2D.Double(x + 2 + offset, centerY + 5, x + 12 + offset, centerY - 5), color, 1.5)
                offset += 4
            }
        }
        canvas.stroke(box, color, 1.0)
    }
}
