/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import dev.ja.marketplace.ChartData
import dev.ja.marketplace.chartImage.*
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.PluginChannelNames
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.PluginInfoSummary
import dev.ja.marketplace.data.chart.ChartDetail
import dev.ja.marketplace.data.chart.ChartMetric
import dev.ja.marketplace.data.chart.ChartMetricValues
import dev.ja.marketplace.data.chart.ChartWindow
import dev.ja.marketplace.data.prices.PriceChanges
import dev.ja.marketplace.data.releases.IdeMajorReleases
import dev.ja.marketplace.data.releases.MajorReleases
import dev.ja.marketplace.mcp.MarketplaceMcpTools.Companion.booleanProperty
import dev.ja.marketplace.mcp.MarketplaceMcpTools.Companion.dateProperty
import dev.ja.marketplace.mcp.MarketplaceMcpTools.Companion.enumProperty
import dev.ja.marketplace.mcp.MarketplaceMcpTools.Companion.integerProperty
import dev.ja.marketplace.mcp.MarketplaceMcpTools.Companion.stringProperty
import dev.ja.marketplace.services.JetBrainsProductCode
import dev.ja.marketplace.services.ProductReleaseType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** The image format of a chart. */
internal enum class ChartFormat(val id: String, val mimeType: String) {
    Png("png", "image/png"),
    Svg("svg", "image/svg+xml"),
}

/**
 * A line of a chart: the values of a metric, or its moving average.
 *
 * @param window The window of the values, `null` to follow the detail level of the chart, like the window
 *  "Follow the level" of the web charts.
 */
internal data class ChartSeriesRequest(
    val metric: ChartMetric,
    val window: ChartWindow? = null,
    val average: Int? = null,
    val style: ChartStyle? = null,
    val color: ChartColor? = null,
)

/**
 * The shown dates of a chart: all dates, or the dates from [start] to [end]. A missing date is the first or the last
 * date with values. Without a [label], e.g. for the dates of `startDate` and `endDate`, the subtitle shows the dates.
 */
internal data class ChartDateRange(val start: LocalDate?, val end: LocalDate?, val label: String?)

/** The arguments of the tool `create_chart`, the options of the chart dialog of the web interface. */
internal data class ChartRequest(
    val series: List<ChartSeriesRequest>,
    val detail: ChartDetail = ChartDetail.Month,
    val window: ChartWindow? = null,
    val style: ChartStyle = ChartStyle.Lines,
    val range: ChartDateRange = ChartDateRange(null, null, "All dates"),
    val previousYear: Boolean = false,
    val showIncomplete: Boolean = true,
    val extremes: Set<ChartExtreme> = emptySet(),
    val hideAxisValues: Boolean = false,
    val markers: Set<ChartMarkerKind> = emptySet(),
    val title: String? = null,
    val format: ChartFormat = ChartFormat.Png,
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val scale: Int = 1,
) {
    companion object {
        const val DEFAULT_WIDTH = 1000
        const val DEFAULT_HEIGHT = 500
        const val MAX_SCALE = 3

        /** The maximum number of lines, `MAX_SERIES` of the web charts. */
        val MAX_SERIES = ChartColor.SeriesColors.size

        /** The ID of the window, which follows the detail level, see `FOLLOW_WINDOW` of `model.js`. */
        const val FOLLOW_WINDOW = "follow"

        val RANGE_PRESETS = listOf("all", "today", "past-7-days", "this-month")

        /** The labels of the windows in the charts, `WINDOW_LABELS` of `model.js`. */
        val windowLabels = mapOf(
            ChartWindow.Day to "Per day",
            ChartWindow.Week to "Last 7 days",
            ChartWindow.Month to "Last month",
            ChartWindow.Quarter to "Last 3 months",
            ChartWindow.Year to "Last 12 months",
            ChartWindow.CalendarMonth to "Calendar month",
            ChartWindow.CalendarQuarter to "Calendar quarter",
            ChartWindow.CalendarYear to "Calendar year",
        )

        /** The IDs of the metrics, the shares of unknown customer types aren't shown, like in `/chart-metrics`. */
        val metricIds: List<String>
            get() = ChartMetric.all.map(ChartMetric::id).filterNot { it.endsWith(".${CustomerType.Unknown}") }

        val windowIds: List<String> get() = listOf(FOLLOW_WINDOW) + ChartWindow.entries.map(ChartWindow::id)

        /**
         * @param today The current day, for the ranges until today.
         * @throws IllegalArgumentException If an argument is invalid.
         */
        fun parse(arguments: JsonObject, today: LocalDate): ChartRequest {
            val window = arguments.stringArgument("window")?.let { windowArgument("window", it) }
            val series = seriesArgument(arguments)
            val format = arguments.choiceArgument("format", ChartFormat.entries.map(ChartFormat::id))
                ?.let { id -> ChartFormat.entries.first { it.id.equals(id, ignoreCase = true) } }
                ?: ChartFormat.Png
            val width = arguments.intArgument("width") ?: DEFAULT_WIDTH
            val height = arguments.intArgument("height") ?: DEFAULT_HEIGHT
            val scale = arguments.intArgument("scale") ?: 1
            require(width in ChartImageRenderer.MIN_WIDTH..ChartImageRenderer.MAX_WIDTH) {
                "width must be between ${ChartImageRenderer.MIN_WIDTH} and ${ChartImageRenderer.MAX_WIDTH}, but was $width"
            }
            require(height in ChartImageRenderer.MIN_HEIGHT..ChartImageRenderer.MAX_HEIGHT) {
                "height must be between ${ChartImageRenderer.MIN_HEIGHT} and ${ChartImageRenderer.MAX_HEIGHT}, but was $height"
            }
            require(scale in 1..MAX_SCALE) { "scale must be between 1 and $MAX_SCALE, but was $scale" }

            return ChartRequest(
                series = series,
                detail = arguments.choiceArgument("detail", ChartDetail.entries.map(ChartDetail::id))
                    ?.let { id -> ChartDetail.entries.first { it.id.equals(id, ignoreCase = true) } }
                    ?: ChartDetail.Month,
                window = window,
                style = arguments.stringArgument("style")?.trim()?.takeIf(String::isNotEmpty)?.let { styleArgument("style", it) } ?: ChartStyle.Lines,
                range = rangeArgument(arguments, today),
                previousYear = arguments.booleanArgument("previousYear") ?: false,
                showIncomplete = arguments.booleanArgument("showIncomplete") ?: true,
                extremes = arguments.stringListArgument("extremes")?.map { id ->
                    ChartExtreme.byId(id.trim())
                        ?: throw IllegalArgumentException("extremes must contain ${ChartExtreme.entries.joinToString { it.id }}, but was '$id'")
                }?.toSet() ?: emptySet(),
                hideAxisValues = arguments.booleanArgument("hideAxisValues") ?: false,
                markers = arguments.stringListArgument("markers")?.map { id ->
                    ChartMarkerKind.byId(id.trim())
                        ?: throw IllegalArgumentException("markers must contain ${ChartMarkerKind.entries.joinToString { it.id }}, but was '$id'")
                }?.toSet() ?: emptySet(),
                title = arguments.stringArgument("title")?.trim()?.takeIf(String::isNotEmpty),
                format = format,
                width = width,
                height = height,
                scale = scale,
            )
        }

        private fun seriesArgument(arguments: JsonObject): List<ChartSeriesRequest> {
            val value = arguments["series"]
            val items = when (value) {
                null, is JsonNull -> throw IllegalArgumentException("series is required, e.g. [{\"metric\": \"sales\"}], metrics: ${metricIds.joinToString()}")
                is JsonArray -> value
                // a single metric or a comma-separated list
                is JsonPrimitive -> JsonArray(value.content.split(',').map(String::trim).filter(String::isNotEmpty).map(::JsonPrimitive))
                else -> throw IllegalArgumentException("series must be an array of objects with a metric")
            }
            require(items.isNotEmpty()) { "series must contain at least one metric" }
            require(items.size <= MAX_SERIES) { "A chart shows at most $MAX_SERIES series, but series has ${items.size}" }
            return items.mapIndexed { index, item -> seriesItem(index, item) }
        }

        private fun seriesItem(index: Int, item: JsonElement): ChartSeriesRequest {
            val name = "series[$index]"
            val properties = when (item) {
                is JsonPrimitive -> buildJsonObject { put("metric", item.content) }
                is JsonObject -> item
                else -> throw IllegalArgumentException("$name must be an object with a metric")
            }
            val unknown = properties.keys - setOf("metric", "window", "average", "style", "color")
            require(unknown.isEmpty()) { "Unknown property ${unknown.joinToString()} of $name, valid properties: metric, window, average, style, color" }

            val metricValue = properties.stringArgument("metric")?.trim()?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("$name.metric is required, metrics: ${metricIds.joinToString()}")
            // the IDs of the moving averages of /chart-data are accepted, e.g. sales@avg3
            val metricId = metricValue.substringBefore(ChartData.AVERAGE_SEPARATOR)
            // the discount amounts of the discounts are metrics of each plugin, see chartMetrics of get_offer_usage
            val metric = ChartMetric.byId(metricId)?.takeIf { it.id in metricIds || it.discountName != null }
                ?: ChartMetric.all.firstOrNull { it.id.equals(metricId, ignoreCase = true) && it.id in metricIds }
                ?: throw IllegalArgumentException("Unknown metric '$metricValue' of $name, metrics: ${metricIds.joinToString()}")
            val averageOfId = when {
                ChartData.AVERAGE_SEPARATOR in metricValue -> metricValue.substringAfter(ChartData.AVERAGE_SEPARATOR).toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid moving average '$metricValue' of $name, e.g. ${metric.id}@avg3")

                else -> null
            }
            val average = properties.intArgument("average") ?: averageOfId
            require(average == null || average in ChartMetricValues.AVERAGE_MONTHS) {
                "$name.average must be one of ${ChartMetricValues.AVERAGE_MONTHS.joinToString()} months, but was $average"
            }
            return ChartSeriesRequest(
                metric = metric,
                window = properties.stringArgument("window")?.let { windowArgument("$name.window", it) },
                average = average,
                style = properties.stringArgument("style")?.trim()?.takeIf(String::isNotEmpty)?.let { styleArgument("$name.style", it) },
                color = properties.stringArgument("color")?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
                    try {
                        ChartColor.parse(value)
                    } catch (_: IllegalArgumentException) {
                        throw IllegalArgumentException("$name.color must be a color like #2a78d6, but was '$value'")
                    }
                },
            )
        }

        /** @return The window, `null` for the window, which follows the detail level. */
        private fun windowArgument(name: String, value: String): ChartWindow? {
            val id = value.trim().lowercase()
            if (id.isEmpty() || id == FOLLOW_WINDOW) {
                return null
            }
            return ChartWindow.byId(id) ?: throw IllegalArgumentException("$name must be one of ${windowIds.joinToString()}, but was '$value'")
        }

        private fun styleArgument(name: String, value: String): ChartStyle {
            return ChartStyle.byId(value) ?: throw IllegalArgumentException("$name must be one of ${ChartStyle.entries.joinToString { it.id }}, but was '$value'")
        }

        private fun rangeArgument(arguments: JsonObject, today: LocalDate): ChartDateRange {
            val range = arguments.stringArgument("range")?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
            val dates = McpDateRange.parse(arguments.stringArgument("startDate"), arguments.stringArgument("endDate"))
            if (!dates.isUnbounded) {
                require(range == null || range == "all") { "Use either range or startDate and endDate" }
                val start = dates.start?.toLocalDate()
                val end = dates.end?.toLocalDate()
                return ChartDateRange(start, end, null)
            }
            return when (range) {
                null, "all" -> ChartDateRange(null, null, "All dates")
                "today" -> ChartDateRange(today, today, "Today")
                "past-7-days" -> ChartDateRange(today.minusDays(6), today, "Past 7 days")
                "this-month" -> ChartDateRange(today.withDayOfMonth(1), today, "This month")
                else -> {
                    val year = range.toIntOrNull()?.takeIf { it in 2000..9999 }
                        ?: throw IllegalArgumentException("range must be one of ${RANGE_PRESETS.joinToString()} or a year like ${today.year}, but was '$range'")
                    ChartDateRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), year.toString())
                }
            }
        }

        /** The approximate days of the steps between the dates of the detail levels, `DETAIL_DAYS` of `model.js`. */
        private fun ChartDetail.approximateDays(): Int = when (this) {
            ChartDetail.Day -> 1
            ChartDetail.Week -> 7
            ChartDetail.Month -> 28
            ChartDetail.Year -> 365
        }

        /** @return The calendar window of the detail level, which the window "follow" uses. */
        fun detailWindow(detail: ChartDetail): ChartWindow = when (detail) {
            ChartDetail.Day -> ChartWindow.Day
            ChartDetail.Week -> ChartWindow.Week
            ChartDetail.Month -> ChartWindow.CalendarMonth
            ChartDetail.Year -> ChartWindow.CalendarYear
        }

        /**
         * @return The detail level of the values of a line, like `lineDetail` of `model.js`: the level of the chart, or
         *  a finer level, if the window is shorter than the steps of the dates, e.g. the values of each day of the
         *  sales per day in a chart of months.
         */
        fun lineDetail(window: ChartWindow, detail: ChartDetail): ChartDetail {
            val details = listOf(ChartDetail.Year, ChartDetail.Month, ChartDetail.Week, ChartDetail.Day)
            return details.drop(details.indexOf(detail)).firstOrNull { window.approximateDays >= it.approximateDays() } ?: ChartDetail.Day
        }

        /** @return The end of the period of the date, so that the value of the current period isn't at the right edge. */
        fun periodEnd(date: LocalDate, detail: ChartDetail): LocalDate = when (detail) {
            ChartDetail.Year -> LocalDate.of(date.year, 12, 31)
            ChartDetail.Month -> date.with(TemporalAdjusters.lastDayOfMonth())
            ChartDetail.Week -> date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            ChartDetail.Day -> date
        }
    }
}

/** A chart with the description of its series, which the tool returns with the image. */
internal class ChartWithSeries(val chart: ChartImage, val series: JsonArray)

/**
 * The tool `create_chart`: renders a line chart of metrics as PNG or SVG, with the options of the chart dialog of the
 * web interface. The values are those of `/chart-data`, see [ChartData] and [ChartMetricValues].
 */
class McpChartTool(private val provider: McpDataProvider) {
    val tools: List<McpToolDefinition> = listOf(
        McpToolDefinition(
            "create_chart",
            "Chart image",
            "Renders a line or bar chart of metrics over time as an image, like the charts of the web interface. " +
                    "Without pluginId, the summary of all paid plugins (fewer metrics). " +
                    "Each series is a metric with its window (the period of the sums and rates at each date, e.g. calendar-month " +
                    "or year = the 12 months before each date; follow = the calendar period of the detail level; " +
                    "values at a date like mrr ignore it) and an optional moving average of 1, 3 or 12 months. " +
                    "At most two units (amounts, counts, %, ratio) on two y-axes. Incomplete values (e.g. the current month) are dotted, " +
                    "partial values (a window before the first data) are hollow. " +
                    "PNG is returned as image content, SVG as text content with the SVG markup. The JSON describes the series and their latest values.",
            buildJsonObject {
                put("pluginId", integerProperty("ID of the plugin, see list_plugins. Default: the summary of all paid plugins"))
                put("series", buildJsonObject {
                    put("type", "array")
                    put("minItems", 1)
                    put("maxItems", ChartRequest.MAX_SERIES)
                    put("description", "The lines of the chart, e.g. [{\"metric\": \"sales\"}, {\"metric\": \"sales\", \"average\": 12}]")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            // not an enum: the discount amounts are metrics of each plugin
                            put("metric", stringProperty(
                                "The metric, one of ${ChartRequest.metricIds.joinToString()}, or the discount amount of an offer or discount " +
                                        "(${ChartMetric.DISCOUNT}.<id>, see chartMetrics of get_offer_usage)"
                            ))
                            put("window", enumProperty(ChartRequest.windowIds, "Window of sums and rates. Default: the window of the chart"))
                            put("average", buildJsonObject {
                                put("type", "integer")
                                put("enum", buildJsonArray { ChartMetricValues.AVERAGE_MONTHS.sorted().forEach { add(it) } })
                                put("description", "Months of a moving average instead of the values. The average of a window, which isn't shorter than the months, is its value")
                            })
                            put("style", enumProperty(ChartStyle.entries.map { it.id }, "Default: the style of the chart. Averages are lines"))
                            put("color", stringProperty("Color like #2a78d6. Default: the next color of the web charts"))
                        })
                        put("required", buildJsonArray { add("metric") })
                    })
                })
                put("detail", enumProperty(ChartDetail.entries.map(ChartDetail::id), "Dates of the values: the end of each year, month, week (Sunday) or each day, and today. Default: month"))
                put("window", enumProperty(ChartRequest.windowIds, "Window of the series without a window. Default: follow"))
                put("style", enumProperty(ChartStyle.entries.map { it.id }, "Default: lines"))
                put("range", stringProperty("Shown dates: ${ChartRequest.RANGE_PRESETS.joinToString()} or a year like 2025. Default: all"))
                put("startDate", dateProperty("First shown day, instead of range"))
                put("endDate", dateProperty("Last shown day, instead of range"))
                put("previousYear", booleanProperty("Add a faint line of the values of the previous year to each series, not at the detail year"))
                put("showIncomplete", booleanProperty("Show values, which may still change. Default: true"))
                put("extremes", buildJsonObject {
                    put("type", "array")
                    put("items", enumProperty(ChartExtreme.entries.map { it.id }))
                    put("description", "Mark the lowest and highest complete value of each series")
                })
                put("hideAxisValues", booleanProperty("Hide the values of the y-axes and extremes, e.g. to share the chart"))
                put("markers", buildJsonObject {
                    put("type", "array")
                    put("items", enumProperty(ChartMarkerKind.entries.map { it.id }))
                    put("description", "Vertical lines: release (major plugin versions), price (changes of the base price), " +
                            "ide (IntelliJ IDEA releases), events (custom events of the server). release and price need a pluginId")
                })
                put("title", stringProperty("Default: the titles of the metrics"))
                put("format", enumProperty(ChartFormat.entries.map(ChartFormat::id), "Default: png"))
                put("width", integerProperty("Width in pixels, ${ChartImageRenderer.MIN_WIDTH} to ${ChartImageRenderer.MAX_WIDTH}. Default: ${ChartRequest.DEFAULT_WIDTH}"))
                put("height", integerProperty("Height in pixels, ${ChartImageRenderer.MIN_HEIGHT} to ${ChartImageRenderer.MAX_HEIGHT}. Default: ${ChartRequest.DEFAULT_HEIGHT}"))
                put("scale", integerProperty("Pixel density of the PNG, 1 to ${ChartRequest.MAX_SCALE}, e.g. 2 for a sharper image with twice the pixels. Default: 1"))
            },
            listOf("series"),
            result = { arguments -> createChart(arguments) },
        ) { arguments -> createChart(arguments).json },
    )

    private suspend fun createChart(arguments: JsonObject): McpToolResult {
        val today = LocalDate.now()
        val request = ChartRequest.parse(arguments, today)
        val plugin = arguments["pluginId"]?.takeUnless { it is JsonNull }?.let { provider.findPlugin(arguments) }
        require(plugin != null || ChartMarkerKind.Release !in request.markers && ChartMarkerKind.Price !in request.markers) {
            "The markers release and price need a pluginId"
        }

        val values = provider.chartValues(plugin)
        val markers = markers(plugin, request.markers)
        val subject = plugin?.name ?: "All paid plugins"
        val (chart, image) = withContext(Dispatchers.Default) {
            val chart = createChartImage(request, values, subject, markers)
            val image = when (request.format) {
                ChartFormat.Png -> McpContent.Image(ChartImageRenderer.png(chart.chart, request.width, request.height, request.scale.toDouble()), ChartFormat.Png.mimeType)
                ChartFormat.Svg -> McpContent.Text(ChartImageRenderer.svg(chart.chart, request.width, request.height))
            }
            chart to image
        }

        val json = buildJsonObject {
            when (plugin) {
                null -> put("allPlugins", true)
                else -> putPlugin(plugin)
            }
            put("title", chart.chart.title)
            put("format", request.format.id)
            put("mimeType", request.format.mimeType)
            put("width", request.width * (if (request.format == ChartFormat.Png) request.scale else 1))
            put("height", request.height * (if (request.format == ChartFormat.Png) request.scale else 1))
            put("detail", request.detail.id)
            put("startDate", chart.chart.start.toString())
            put("endDate", chart.chart.end.toString())
            put("currency", values.currency)
            put("series", chart.series)
            if (markers.isNotEmpty()) {
                put("markers", buildJsonArray {
                    markers.filter { it.date >= chart.chart.start && it.date <= chart.chart.end }.forEach { marker ->
                        add(buildJsonObject {
                            put("date", marker.date.toString())
                            put("kind", marker.kind.id)
                            put("label", marker.label)
                        })
                    }
                })
            }
        }
        return McpToolResult(json, listOf(image))
    }

    /**
     * Loads the vertical lines of the kinds. Like the web charts, a chart is created without the lines, which can't
     * be loaded, e.g. the releases of the IDE.
     */
    private suspend fun markers(plugin: PluginInfoSummary?, kinds: Set<ChartMarkerKind>): List<ChartMarker> {
        return kinds.flatMap { kind ->
            try {
                when (kind) {
                    ChartMarkerKind.Release -> MajorReleases.find(provider.pluginReleases(plugin!!, PluginChannelNames.Stable))
                        .map { ChartMarker(it.date.toLocalDate(), it.version, kind) }

                    ChartMarkerKind.Price -> PriceChanges.find(provider.pluginData(plugin!!).getSales() ?: emptyList())
                        .map { ChartMarker(it.date.toLocalDate(), it.label, kind) }

                    ChartMarkerKind.Ide -> IdeMajorReleases.find(provider.productReleases(JetBrainsProductCode.IntelliJIDEAUltimate, ProductReleaseType.Release))
                        .filter { it.date >= Marketplace.Birthday }
                        .map { ChartMarker(it.date.toLocalDate(), it.version, kind) }

                    ChartMarkerKind.Event -> {
                        val plugins = plugin?.let { listOf(it.id) } ?: provider.plugins().filter(PluginInfoSummary::isPaidOrFreemium).map(PluginInfoSummary::id)
                        provider.customEvents(plugins).map { ChartMarker(it.date.toLocalDate(), it.label, kind) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    internal companion object {
        /**
         * @param subject The plugin or the summary of all plugins, for the subtitle and the errors.
         * @throws IllegalArgumentException If a metric has no values, an average doesn't apply, or for more than two units.
         */
        fun createChartImage(request: ChartRequest, values: ChartMetricValues, subject: String, markers: List<ChartMarker>): ChartWithSeries {
            val usedColors = request.series.mapNotNull(ChartSeriesRequest::color).toMutableSet()
            // the colors of the previous series, all series are in one chart
            val neighbors = mutableListOf<Pair<ChartColor, ChartStyle>>()
            val seriesJson = mutableListOf<JsonObject>()
            val series = request.series.flatMapIndexed { index, item ->
                val metric = item.metric
                require(metric in values.metrics) {
                    val available = ChartData.metrics(values).map { it.id }
                    "The metric ${metric.id} has no values for $subject, metrics with values: ${available.joinToString()}"
                }
                val window = when {
                    metric.usesWindow -> item.window ?: request.window ?: ChartRequest.detailWindow(request.detail)
                    else -> ChartRequest.detailWindow(request.detail)
                }
                val detail = ChartRequest.lineDetail(window, request.detail)
                val id = metric.id + (item.average?.let { "${ChartData.AVERAGE_SEPARATOR}$it" } ?: "")
                val response = ChartData.chartData(values, listOf(id), window, detail)
                val data = response.series[id] ?: throw IllegalArgumentException("There are no values of $id for $subject")

                val points = response.dates.indices.mapNotNull { dateIndex ->
                    val value = data.values[dateIndex] ?: return@mapNotNull null
                    ChartPoint(
                        LocalDate.parse(response.dates[dateIndex]),
                        value.toDouble(),
                        isIncomplete = dateIndex >= data.incompleteFrom,
                        isPartial = dateIndex < data.partialUntil,
                    )
                }.filter { request.showIncomplete || !it.isIncomplete }

                val label = (if (metric.usesWindow) "${metric.title}, ${ChartRequest.windowLabels.getValue(window).lowercase()}" else metric.title) +
                        (item.average?.let { ", $it-month average" } ?: "")
                val style = if (item.average != null) ChartStyle.Lines else item.style ?: request.style
                val color = item.color ?: ChartColor.next(usedColors, neighbors, style) ?: ChartColor.SeriesColors[index % ChartColor.SeriesColors.size]
                usedColors += color
                neighbors += color to style
                val kind = if (item.average != null) ChartSeriesKind.Average else ChartSeriesKind.Values
                val valueSeries = ChartImageSeries(label, data.unit, color, points, style, kind, item.average, data.fractionDigits)

                seriesJson += buildJsonObject {
                    put("metric", metric.id)
                    put("label", label)
                    put("unit", data.unit)
                    if (metric.usesWindow) put("window", window.id)
                    item.average?.let { put("averageMonths", it) }
                    put("detail", detail.id)
                    put("style", style.id)
                    put("color", color.hex)
                    put("valueCount", points.size)
                    points.lastOrNull()?.let { latest ->
                        put("latest", buildJsonObject {
                            put("date", latest.date.toString())
                            put("value", latest.value)
                            if (latest.isIncomplete) put("incomplete", true)
                        })
                    }
                }

                // the previous year of the values, it can't be compared at the level of years
                val previousYear = when {
                    request.previousYear && item.average == null && detail != ChartDetail.Year && points.isNotEmpty() -> {
                        val last = points.last().date
                        val shifted = points.map { it.copy(date = it.date.plusYears(1), isPartial = false) }.filter { it.date <= last }
                        ChartImageSeries("$label, previous year", data.unit, color, shifted, ChartStyle.Lines, ChartSeriesKind.PreviousYear, fractionDigits = data.fractionDigits)
                    }

                    else -> null
                }
                listOfNotNull(valueSeries, previousYear)
            }

            val units = series.map(ChartImageSeries::unit).distinct()
            require(units.size <= 2) {
                "The series have ${units.size} units (${units.joinToString()}), a chart shows at most two units on two y-axes. Create a chart for each unit"
            }

            val valuePoints = series.filter { it.kind != ChartSeriesKind.PreviousYear }.flatMap(ChartImageSeries::points)
            require(valuePoints.isNotEmpty()) { "There are no values of the series for $subject" }
            val start = request.range.start ?: valuePoints.minOf(ChartPoint::date)
            val end = request.range.end ?: ChartRequest.periodEnd(valuePoints.maxOf(ChartPoint::date), request.detail)
            require(start <= end) { "The shown dates end before they start: $start – $end" }

            val title = request.title ?: request.series.map { it.metric.title }.distinct().joinToString(" and ")
            // the dates of the values instead of the end of the last period, which may be after today
            val rangeLabel = request.range.label?.replaceFirstChar(Char::lowercaseChar)
                ?: "${request.range.start ?: start} – ${request.range.end ?: valuePoints.maxOf(ChartPoint::date)}"
            val subtitle = listOf(subject, "values of each ${request.detail.id}", rangeLabel)
                .joinToString(" · ")
            val chart = ChartImage(
                title = title,
                subtitle = subtitle,
                series = series,
                markers = markers,
                detail = request.detail,
                start = start,
                end = end,
                extremes = request.extremes,
                hideAxisValues = request.hideAxisValues,
            )
            return ChartWithSeries(chart, JsonArray(seriesJson))
        }
    }
}
