/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.chartImage

import dev.ja.marketplace.data.chart.ChartDetail
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A line chart of the values of metrics over time, which is rendered on the server as SVG or PNG by
 * [ChartImageRenderer], e.g. by the MCP tool `create_chart`. It looks like the line charts of the web interface
 * (`lineChart.js`) in the light theme, but is drawn without uPlot.
 *
 * @param series The lines and bars, at most two units, the first unit has the left y-axis.
 * @param markers Vertical lines, e.g. major releases.
 * @param detail The detail level of the chart, the date axis has ticks of it.
 * @param start The first shown date.
 * @param end The last shown date. Values outside of the dates aren't drawn and don't change the y-axes.
 * @param extremes Marks the lowest and highest complete value of each series of values.
 * @param hideAxisValues Hides the values of the y-axes and of the extremes, e.g. to share a chart without numbers.
 */
class ChartImage(
    val title: String,
    val subtitle: String?,
    val series: List<ChartImageSeries>,
    val markers: List<ChartMarker>,
    val detail: ChartDetail,
    val start: LocalDate,
    val end: LocalDate,
    val extremes: Set<ChartExtreme> = emptySet(),
    val hideAxisValues: Boolean = false,
) {
    /** The units of the series, the first one has the left y-axis, the second one the right y-axis. */
    val units: List<String> = series.map(ChartImageSeries::unit).distinct()

    init {
        require(units.size <= 2) { "A chart shows at most two units, but has ${units.joinToString()}" }
        require(start <= end) { "The start $start is after the end $end" }
    }
}

/**
 * A value of a series. Incomplete values may still change, e.g. of the current month. Partial values don't cover their
 * whole window, e.g. of the first year of a plugin, which started in February.
 */
data class ChartPoint(val date: LocalDate, val value: Double, val isIncomplete: Boolean = false, val isPartial: Boolean = false)

enum class ChartStyle(val id: String) {
    Lines("lines"),
    Bars("bars");

    companion object {
        fun byId(id: String): ChartStyle? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** The kind of a series: values of a metric, its moving average or the values of the previous year of a series. */
enum class ChartSeriesKind {
    Values,

    /** Dashed lines, if the chart also shows values, without points. */
    Average,

    /** A faint line of the values one year later, without points. */
    PreviousYear,
}

/**
 * @param averageMonths The months of a moving average, for its dashes.
 * @param fractionDigits The maximum number of decimals of the labels of the values, see `ChartMetric.fractionDigits`.
 */
class ChartImageSeries(
    val label: String,
    val unit: String,
    val color: ChartColor,
    val points: List<ChartPoint>,
    val style: ChartStyle = ChartStyle.Lines,
    val kind: ChartSeriesKind = ChartSeriesKind.Values,
    val averageMonths: Int? = null,
    val fractionDigits: Int = 2,
) {
    /** Moving averages and the previous year are always lines. */
    val isBar: Boolean get() = style == ChartStyle.Bars && kind == ChartSeriesKind.Values
}

enum class ChartExtreme(val id: String) {
    Min("min"),
    Max("max");

    companion object {
        fun byId(id: String): ChartExtreme? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * The kinds of vertical lines with their colors and dashes, like `CHART_STYLE.markers` and the light theme of the
 * web interface.
 */
enum class ChartMarkerKind(val id: String, val label: String, val color: ChartColor, val dash: List<Double>) {
    Release("release", "Product version", ChartColor.parse("#7d7c77"), listOf(4.0, 4.0)),
    Price("price", "Price changes", ChartColor.parse("#c27c0e"), listOf(2.0, 3.0)),
    Ide("ide", "IntelliJ IDEA releases", ChartColor.parse("#6f74b8"), listOf(8.0, 3.0)),
    Event("events", "Events", ChartColor.parse("#1f8a5b"), listOf(10.0, 3.0, 2.0, 3.0));

    companion object {
        fun byId(id: String): ChartMarkerKind? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** A vertical line with a label above the plot, e.g. a major release. */
data class ChartMarker(val date: LocalDate, val label: String, val kind: ChartMarkerKind)

/**
 * A color of the charts with an opacity from 0 to 255.
 */
data class ChartColor(val rgb: Int, val alpha: Int = 255) {
    /** The color without the opacity, e.g. `#2a78d6`. */
    val hex: String get() = "#%06x".format(rgb)

    fun withAlpha(alpha: Int): ChartColor = copy(alpha = alpha)

    fun toAwt(): java.awt.Color = java.awt.Color(rgb shr 16 and 0xff, rgb shr 8 and 0xff, rgb and 0xff, alpha)

    /**
     * Like `incompleteColor` of `model.js`: the color with less saturation and more lightness for incomplete values,
     * or [IncompleteGrey], if that's too similar to the color.
     */
    fun incomplete(): ChartColor {
        val (hue, saturation, lightness) = hsl()
        val result = fromHsl(hue, saturation * 0.8, lightness + (1 - lightness) * 0.3)
        val distance = hypot(hypot((red - result.red).toDouble(), (green - result.green).toDouble()), (blue - result.blue).toDouble())
        return if (distance < 70) IncompleteGrey else result
    }

    private fun linearRgb(): DoubleArray = intArrayOf(red, green, blue).let { channels ->
        DoubleArray(3) { i ->
            val value = channels[i] / 255.0
            if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
    }

    private val red: Int get() = rgb shr 16 and 0xff
    private val green: Int get() = rgb shr 8 and 0xff
    private val blue: Int get() = rgb and 0xff

    private fun hsl(): Triple<Double, Double, Double> {
        val r = red / 255.0
        val g = green / 255.0
        val b = blue / 255.0
        val max = max(r, max(g, b))
        val min = min(r, min(g, b))
        val lightness = (max + min) / 2
        if (max == min) {
            return Triple(0.0, 0.0, lightness)
        }
        val delta = max - min
        val saturation = if (lightness > 0.5) delta / (2 - max - min) else delta / (max + min)
        val hue = when (max) {
            r -> (g - b) / delta + (if (g < b) 6 else 0)
            g -> (b - r) / delta + 2
            else -> (r - g) / delta + 4
        }
        return Triple(hue / 6, saturation, lightness)
    }

    companion object {
        /** The colors of the series, `SERIES_COLORS` of `model.js`. */
        val SeriesColors: List<ChartColor> = listOf("#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948").map(::parse)

        val IncompleteGrey: ChartColor = parse("#a8a7a2")

        /** `MIN_COLOR_DISTANCE` of `model.js`: the smallest distance of a new color to the colors of its chart. */
        const val MIN_DISTANCE: Double = 0.22

        /**
         * Like `nextColor` of `model.js`: the first color of [SeriesColors], which isn't used yet and is distinct from
         * the colors of the other series of the chart, otherwise the unused color, which is the most distinct from them.
         *
         * @param neighbors The colors and the styles of the other series of the chart.
         * @return The color, `null` if all colors are used.
         */
        fun next(usedColors: Set<ChartColor>, neighbors: List<Pair<ChartColor, ChartStyle>>, style: ChartStyle): ChartColor? {
            val unused = SeriesColors.filter { it !in usedColors }
            val scores = unused.map { color ->
                neighbors.minOfOrNull { (neighbor, neighborStyle) -> distance(color, neighbor, style != neighborStyle) } ?: Double.POSITIVE_INFINITY
            }
            val firstDistinct = unused.indices.firstOrNull { scores[it] >= MIN_DISTANCE }
            // the first of equal scores is the earliest color of the palette
            return unused.getOrNull(firstDistinct ?: scores.indices.maxByOrNull { scores[it] } ?: return null)
        }

        /**
         * Like `colorDistance` of `model.js`: the distance in the Oklab color space, the smallest of normal vision and
         * of the simulated color vision deficiencies. The distance of a line and a bar is at most twice the difference
         * of their lightness.
         */
        fun distance(first: ChartColor, second: ChartColor, lineOverBar: Boolean = false): Double {
            val a = first.linearRgb()
            val b = second.linearRgb()
            val labA = oklab(a)
            val labB = oklab(b)
            var distance = labDistance(labA, labB)
            for (matrix in VisionDeficiencies) {
                distance = min(distance, labDistance(oklab(simulate(matrix, a)), oklab(simulate(matrix, b))))
            }
            return if (lineOverBar) min(distance, 2 * abs(labA[0] - labB[0])) else distance
        }

        /** Deuteranopia and protanopia in linear RGB (Machado et al. 2009), `VISION_DEFICIENCIES` of `model.js`. */
        private val VisionDeficiencies = listOf(
            arrayOf(doubleArrayOf(0.367322, 0.860646, -0.227968), doubleArrayOf(0.280085, 0.672501, 0.047413), doubleArrayOf(-0.011820, 0.042940, 0.968881)),
            arrayOf(doubleArrayOf(0.152286, 1.052583, -0.204868), doubleArrayOf(0.114503, 0.786281, 0.099216), doubleArrayOf(-0.003882, -0.048116, 1.051998)),
        )

        private fun simulate(matrix: Array<DoubleArray>, rgb: DoubleArray): DoubleArray =
            DoubleArray(3) { row -> (matrix[row][0] * rgb[0] + matrix[row][1] * rgb[1] + matrix[row][2] * rgb[2]).coerceIn(0.0, 1.0) }

        private fun labDistance(a: DoubleArray, b: DoubleArray): Double = hypot(hypot(a[0] - b[0], a[1] - b[1]), a[2] - b[2])

        private fun oklab(rgb: DoubleArray): DoubleArray {
            val (r, g, b) = rgb
            val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            return doubleArrayOf(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
            )
        }

        /** The colors of the light theme of the web charts, `CHART_THEMES.light` of `lineChart.js`. */
        val Axis: ChartColor = parse("#52514e")
        val Grid: ChartColor = parse("#e4e3df")
        val Surface: ChartColor = parse("#ffffff")
        val Text: ChartColor = parse("#1c1b22")

        /**
         * @param value A color like `#2a78d6`.
         * @throws IllegalArgumentException If it's no color in this format.
         */
        fun parse(value: String): ChartColor {
            val match = Regex("^#?([0-9a-fA-F]{6})$").matchEntire(value.trim())
                ?: throw IllegalArgumentException("A color must have the format #rrggbb, but was '$value'")
            return ChartColor(match.groupValues[1].toInt(16))
        }

        private fun fromHsl(hue: Double, saturation: Double, lightness: Double): ChartColor {
            val a = saturation * min(lightness, 1 - lightness)
            fun channel(n: Int): Int {
                val k = (n + hue * 12) % 12
                val value = lightness - a * max(-1.0, min(min(k - 3, 9 - k), 1.0))
                return (value * 255).roundToInt().coerceIn(0, 255)
            }
            return ChartColor(channel(0) shl 16 or (channel(8) shl 8) or channel(4))
        }
    }
}

/** @return If the values are the same, apart from rounding errors. */
internal fun nearlyEqual(a: Double, b: Double): Boolean = abs(a - b) <= 1e-9 * max(1.0, max(abs(a), abs(b)))
