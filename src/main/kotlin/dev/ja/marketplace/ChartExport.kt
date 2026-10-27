/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.chart.ChartDetail
import dev.ja.marketplace.data.chart.ChartMetric
import dev.ja.marketplace.data.chart.ChartMetricValues
import dev.ja.marketplace.data.chart.ChartWindow
import dev.ja.marketplace.data.chart.DailySeries
import io.ktor.http.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The values of the lines of a chart of metrics in a machine-readable form, served at `/chart-export`, e.g. for the
 * action "Copy data URL" of the charts, whose URL can be given to an AI agent.
 *
 * A single URL describes the lines of a chart: each line has its metric, its window, optionally a moving average
 * and the values of the previous year, e.g.
 * `/chart-export?pluginId=1&detail=month&lines=sales:calendar-month,sales@avg3:calendar-month,mrr@previous-year&from=2025-01-01`.
 *
 * The default format is CSV with one row per date and one column per line: it's the most compact form for agents and
 * spreadsheets, and the headers contain the titles, windows and units. `format=json` adds the metadata, e.g. the
 * plugin, the units, the measures and the incomplete and partial values of each line.
 */
object ChartExport {
    /** The maximum number of lines of a request. */
    const val MAX_LINES = ChartData.MAX_METRICS

    /** The suffix of a line with the values one year before each date, e.g. `sales@previous-year`. */
    const val PREVIOUS_YEAR = "previous-year"

    /** The labels of the windows, like in the charts, see `WINDOW_LABELS` in `model.js`. */
    private val windowLabels = mapOf(
        ChartWindow.Day to "per day",
        ChartWindow.Week to "last 7 days",
        ChartWindow.Month to "last month",
        ChartWindow.Quarter to "last 3 months",
        ChartWindow.Year to "last 12 months",
        ChartWindow.CalendarMonth to "calendar month",
        ChartWindow.CalendarQuarter to "calendar quarter",
        ChartWindow.CalendarYear to "calendar year",
    )

    /** The approximate days between the dates of the detail levels, see `DETAIL_DAYS` in `model.js`. */
    private val detailDays = mapOf(ChartDetail.Day to 1, ChartDetail.Week to 7, ChartDetail.Month to 28, ChartDetail.Year to 365)

    enum class Format(val id: String, val contentType: ContentType) {
        Csv("csv", ContentType.Text.CSV.withCharset(Charsets.UTF_8)),
        Json("json", ContentType.Application.Json);

        companion object {
            fun byId(id: String): Format? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * A line of the export.
     *
     * @param id The line as in the request, e.g. `sales@avg3:calendar-month`.
     * @param window The window of the values, ignored by metrics, which don't use it.
     */
    data class Line(val id: String, val metric: ChartMetric, val averageMonths: Int?, val window: ChartWindow, val previousYear: Boolean)

    /**
     * @param from The first date of the values, `null` for all dates.
     * @param to The last date of the values, `null` until today.
     * @param includeIncomplete If values, which may still change, are exported, e.g. of the current month.
     */
    data class Request(
        val lines: List<Line>,
        val detail: ChartDetail,
        val from: YearMonthDay?,
        val to: YearMonthDay?,
        val format: Format,
        val includeIncomplete: Boolean,
    )

    /** An invalid parameter of a request, its message is the answer of the request. */
    class InvalidRequestException(message: String) : IllegalArgumentException(message)

    /**
     * @return The request of the parameters.
     * @throws InvalidRequestException for invalid parameters.
     */
    fun parse(parameters: Parameters): Request {
        fun invalid(message: String): Nothing = throw InvalidRequestException(message)
        fun date(name: String): YearMonthDay? = parameters[name]?.takeIf(String::isNotBlank)?.let {
            runCatching { YearMonthDay.parse(it.trim()) }.getOrNull() ?: invalid("Invalid $name: expected an ISO date like 2026-01-31")
        }

        val detail = parameters["detail"]?.let { ChartDetail.byId(it) ?: invalid("Invalid detail: expected one of ${ChartDetail.entries.joinToString { it.id }}") }
            ?: ChartDetail.Month
        val format = parameters["format"]?.let { Format.byId(it) ?: invalid("Invalid format: expected csv or json") } ?: Format.Csv
        val includeIncomplete = when (parameters["incomplete"]) {
            null, "true" -> true
            "false" -> false
            else -> invalid("Invalid incomplete: expected true or false")
        }
        val lineIds = parameters["lines"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.distinct() ?: emptyList()
        if (lineIds.isEmpty() || lineIds.size > MAX_LINES) {
            invalid("Invalid lines: expected 1 to $MAX_LINES lines like sales:calendar-month,sales@avg3,mrr@$PREVIOUS_YEAR")
        }
        val from = date("from")
        val to = date("to")
        if (from != null && to != null && from > to) {
            invalid("Invalid range: from is after to")
        }
        return Request(lineIds.map { parseLine(it, detail) ?: invalid("Invalid line: $it") }, detail, from, to, format, includeIncomplete)
    }

    /**
     * @param id A line, e.g. `sales@avg3@previous-year:calendar-month`: the metric, optionally its moving average and
     *  the values of the previous year, and the window. The window defaults to the calendar window of the [detail].
     */
    private fun parseLine(id: String, detail: ChartDetail): Line? {
        val window = when (val windowId = id.substringAfter(':', "")) {
            "" -> calendarWindow(detail)
            else -> ChartWindow.byId(windowId) ?: return null
        }
        val parts = id.substringBefore(':').split('@')
        val metric = ChartMetric.byId(parts.first()) ?: return null
        var averageMonths: Int? = null
        var previousYear = false
        for (part in parts.drop(1)) {
            when {
                part == PREVIOUS_YEAR && !previousYear -> previousYear = true
                part.startsWith("avg") && averageMonths == null && !previousYear -> {
                    averageMonths = part.removePrefix("avg").toIntOrNull()?.takeIf { it in ChartMetricValues.AVERAGE_MONTHS } ?: return null
                }

                else -> return null
            }
        }
        return Line(id, metric, averageMonths, window, previousYear)
    }

    /** @return The window, which follows the detail level, see `shownWindow` in `model.js`. */
    fun calendarWindow(detail: ChartDetail): ChartWindow = when (detail) {
        ChartDetail.Day -> ChartWindow.Day
        ChartDetail.Week -> ChartWindow.Week
        ChartDetail.Month -> ChartWindow.CalendarMonth
        ChartDetail.Year -> ChartWindow.CalendarYear
    }

    /**
     * @return The detail level of the values of a line: the [detail] of the chart, or a finer level, if the window is
     *  shorter than the steps of the dates, e.g. each day for the sales per day in a chart of months. See `lineDetail`
     *  in `model.js`.
     */
    fun lineDetail(window: ChartWindow, detail: ChartDetail): ChartDetail {
        fun applies(d: ChartDetail) = window.approximateDays >= detailDays.getValue(d)
        return when {
            applies(detail) -> detail
            else -> listOf(ChartDetail.Year, ChartDetail.Month, ChartDetail.Week, ChartDetail.Day).firstOrNull(::applies) ?: ChartDetail.Day
        }
    }

    /** @return The label of a line like in the legend of the charts, e.g. "Sales before fees, calendar month, 3-month average". */
    fun label(line: Line): String = buildString {
        append(line.metric.title)
        if (line.metric.usesWindow) {
            append(", ").append(windowLabels.getValue(line.window))
        }
        line.averageMonths?.let { append(", ").append(it).append("-month average") }
        if (line.previousYear) {
            append(", previous year")
        }
    }

    /**
     * The values of the lines at their dates.
     *
     * @param plugin The ID and the name of the plugin, `null` for the summary of all paid plugins.
     * @param omitted The lines without values, e.g. the trials in the summary of all plugins, or averages, which don't
     *  apply to the window.
     */
    class Table(
        val plugin: Pair<Int, String>?,
        val currency: String,
        val request: Request,
        val columns: List<Column>,
        val dates: List<YearMonthDay>,
        val omitted: List<String>,
    )

    /**
     * @param values The value at each date of [Table.dates], `null` without a value at the date.
     * @param incomplete The indices of the values, which may still change, e.g. of the current month.
     * @param partial The indices of the values, whose window starts before the first data, e.g. the first year of a
     *  plugin, which started in February.
     */
    class Column(val line: Line, val unit: String, val detail: ChartDetail, val values: List<BigDecimal?>, val incomplete: Set<Int>, val partial: Set<Int>)

    private class LineValues(val line: Line, val unit: String, val detail: ChartDetail, val values: Map<YearMonthDay, Value>)

    private class Value(val value: BigDecimal?, val incomplete: Boolean, val partial: Boolean)

    /** @return The values of the lines of the [request]. */
    fun table(values: ChartMetricValues, request: Request, plugin: Pair<Int, String>?): Table {
        val days = values.days
        val dayIndices = days.withIndex().associate { (index, day) -> day to index }
        val omitted = mutableListOf<String>()
        val lines = request.lines.mapNotNull { line ->
            val daily = when (line.averageMonths) {
                null -> values.series(line.metric, line.window)
                else -> values.average(line.metric, line.window, line.averageMonths)
            }
            if (daily == null) {
                omitted += line.id
                return@mapNotNull null
            }
            val detail = if (line.metric.usesWindow) lineDetail(line.window, request.detail) else request.detail
            val lineValues = LinkedHashMap<YearMonthDay, Value>()
            for (index in detail.indices(days)) {
                val sourceIndex = when {
                    line.previousYear -> dayIndices[previousYear(days[index])] ?: continue
                    else -> index
                }
                lineValues[days[index]] = daily.value(sourceIndex)
            }
            LineValues(line, daily.unit, detail, lineValues)
        }

        // the values start with the first date, at which a line has a value, like in the charts
        val allDates = lines.flatMap { it.values.keys }.toSortedSet()
        val firstValue = allDates.firstOrNull { date -> lines.any { it.values[date]?.value?.signum()?.let { s -> s != 0 } == true } }
        val dates = allDates.filter { date ->
            (firstValue == null || date >= firstValue)
                    && (request.from == null || date >= request.from)
                    && (request.to == null || date <= request.to)
        }

        val columns = lines.map { line ->
            val shown = dates.map { date -> line.values[date]?.takeIf { request.includeIncomplete || !it.incomplete } }
            Column(
                line.line, line.unit, line.detail,
                shown.map { it?.value },
                shown.indices.filter { shown[it]?.incomplete == true }.toSet(),
                shown.indices.filter { shown[it]?.partial == true }.toSet(),
            )
        }
        return Table(plugin, values.currency, request, columns, dates, omitted)
    }

    private fun DailySeries.value(index: Int): Value {
        return Value(values[index], index >= incompleteFrom, index < partialUntil && values[index]?.signum() != 0)
    }

    /**
     * @return The day one year before [day], the last day of a month is the last day of the month of the previous
     *  year, like the comparison with the previous year of the charts.
     */
    fun previousYear(day: YearMonthDay): YearMonthDay {
        return when (day) {
            YearMonthDay.lastOfMonth(day.year, day.month) -> YearMonthDay.lastOfMonth(day.year - 1, day.month)
            else -> day.add(-1, 0, 0)
        }
    }

    /** @return The header of a column: the label of the line and its unit, e.g. "Sales before fees, calendar month (USD)". */
    private fun header(column: Column): String = "${label(column.line)} (${column.unit})"

    /**
     * The values as CSV (RFC 4180): the date and one column per line, the headers contain the label and the unit of
     * each line. Numbers have no grouping and a dot as decimal separator, dates are ISO dates. An empty cell has no
     * value. The last column `status` marks the dates, at which values are `incomplete` (they may still change, e.g.
     * the current month) or `partial` (the window starts before the first data).
     */
    fun csv(table: Table): String = buildString {
        fun cell(text: String) = if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${text.replace("\"", "\"\"")}\"" else text

        append((listOf("date") + table.columns.map(::header) + "status").joinToString(",", transform = ::cell)).append("\r\n")
        table.dates.forEachIndexed { index, date ->
            val status = listOfNotNull(
                "incomplete".takeIf { table.columns.any { index in it.incomplete } },
                "partial".takeIf { table.columns.any { index in it.partial } },
            ).joinToString(" ")
            val cells = listOf(date.asIsoString) + table.columns.map { it.values[index]?.toPlainString() ?: "" } + status
            append(cells.joinToString(",", transform = ::cell)).append("\r\n")
        }
    }

    /** @return The values as JSON with the metadata of the export and of each column, see [ChartExportJson]. */
    fun json(table: Table, generated: Instant = Instant.now()): ChartExportJson {
        val request = table.request
        return ChartExportJson(
            description = "Values of the lines of a chart of JetBrains Marketplace statistics. Each row is the date followed by the value of each column, null without a value.",
            pluginId = table.plugin?.first,
            pluginName = table.plugin?.second ?: "Summary of all paid plugins",
            generated = generated.truncatedTo(ChronoUnit.SECONDS).toString(),
            currency = table.currency,
            detail = request.detail.id,
            from = request.from?.asIsoString,
            to = request.to?.asIsoString,
            columns = table.columns.map { column ->
                val line = column.line
                ChartExportColumn(
                    id = line.id,
                    label = label(line),
                    metric = line.metric.id,
                    title = line.metric.title,
                    unit = column.unit,
                    measure = line.metric.measure.name.lowercase(),
                    window = line.window.id.takeIf { line.metric.usesWindow },
                    detail = column.detail.id,
                    averageMonths = line.averageMonths,
                    previousYear = line.previousYear.takeIf { it },
                    incompleteDates = column.incomplete.sorted().map { table.dates[it].asIsoString },
                    partialDates = column.partial.sorted().map { table.dates[it].asIsoString },
                    fractionDigits = line.metric.fractionDigits,
                )
            },
            omitted = table.omitted.takeIf { it.isNotEmpty() },
            rows = table.dates.mapIndexed { index, date -> ChartExportRow(date.asIsoString, table.columns.map { it.values[index] }) },
        )
    }
}

/**
 * The JSON of `/chart-export?format=json`.
 *
 * @param rows The values: the date and the value of each column.
 */
@Serializable
class ChartExportJson(
    val description: String,
    val pluginId: Int?,
    val pluginName: String,
    val generated: String,
    val currency: String,
    val detail: String,
    val from: String?,
    val to: String?,
    val columns: List<ChartExportColumn>,
    val omitted: List<String>?,
    val rows: List<ChartExportRow>,
)

/**
 * A line of `/chart-export?format=json`.
 *
 * @param measure `flow` (sums of the window), `stock` (values at the date) or `ratio`.
 * @param window The window of sums and rates, `null` for values at the date.
 * @param incompleteDates The dates of values, which may still change, e.g. of the current month.
 * @param partialDates The dates of values, whose window starts before the first data.
 */
@Serializable
class ChartExportColumn(
    val id: String,
    val label: String,
    val metric: String,
    val title: String,
    val unit: String,
    val measure: String,
    val window: String?,
    val detail: String,
    val averageMonths: Int?,
    val previousYear: Boolean?,
    val incompleteDates: List<String>,
    val partialDates: List<String>,
    /** The maximum number of decimals to show the values, see [ChartMetric.fractionDigits], the values aren't rounded. */
    val fractionDigits: Int,
)

/** A row of `/chart-export?format=json`, written as array: `["2026-01-31", 12.50, null]`. */
@Serializable(with = ChartExportRowSerializer::class)
class ChartExportRow(val date: String, val values: List<BigDecimal?>)

/**
 * Writes a [ChartExportRow] as JSON array of the date and the values, the values as numbers with their scale.
 * It's as visible as the row, the serializer of a type must not be less visible than the type.
 */
object ChartExportRowSerializer : KSerializer<ChartExportRow> {
    override val descriptor: SerialDescriptor = JsonArray.serializer().descriptor

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: ChartExportRow) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("The rows of the chart export are only written as JSON")
        val values = value.values.map { it?.let { decimal -> JsonUnquotedLiteral(decimal.toPlainString()) } ?: JsonNull }
        json.encodeJsonElement(JsonArray(listOf(JsonPrimitive(value.date)) + values))
    }

    override fun deserialize(decoder: Decoder): ChartExportRow {
        val json = decoder as? JsonDecoder ?: throw SerializationException("The rows of the chart export are only read as JSON")
        val array = json.decodeJsonElement().jsonArray
        return ChartExportRow(array.first().jsonPrimitive.content, array.drop(1).map { it.jsonPrimitive.contentOrNull?.toBigDecimal() })
    }
}
