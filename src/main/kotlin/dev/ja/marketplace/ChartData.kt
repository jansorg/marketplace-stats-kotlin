/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.PluginData
import dev.ja.marketplace.data.chart.ChartDetail
import dev.ja.marketplace.data.chart.ChartDownloadsSource
import dev.ja.marketplace.data.chart.ChartMetricFamily
import dev.ja.marketplace.data.chart.ChartMetric
import dev.ja.marketplace.data.chart.ChartMetricValues
import dev.ja.marketplace.data.chart.ChartWindow
import dev.ja.marketplace.data.trackers.maxTrialDays
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * The values of the [ChartMetric]s of the plugins as JSON for the charts, served at `/chart-data`.
 *
 * The values of a plugin are calculated for the loaded data of the plugin, the values of a metric and a window when
 * they're requested first. The client caches the loaded data, so the values are calculated again when the data is
 * reloaded or on the next day.
 *
 * @param downloadsSource The source of the downloads of the releases and products of a plugin, see
 *  [ChartDownloadsSource], `null` without these metrics.
 */
class ChartData(private val downloadsSource: ((PluginId) -> ChartDownloadsSource)? = null) {
    private class Entry(
        val sales: Any?,
        val trials: Any?,
        val downloads: Any?,
        val licenses: Any?,
        val currency: String,
        val today: YearMonthDay,
        val values: ChartMetricValues,
    )

    private val entries = ConcurrentHashMap<PluginId, Entry>()

    /** @return The values of the metrics of the plugin of [data]. */
    suspend fun values(data: PluginData): ChartMetricValues {
        val sales = data.getSales()
        val trials = data.getTrials()
        val downloads = data.getDownloadsDaily()
        val licenses = data.getLicenses()
        val currency = data.exchangeRates.targetCurrency.currencyCode
        val today = YearMonthDay.now()

        fun Entry.isValid() = this.sales === sales && this.trials === trials && this.downloads === downloads
                && this.licenses === licenses && this.currency == currency && this.today == today
        entries[data.pluginId]?.takeIf { it.isValid() }?.let { return it.values }

        val maxTrialDays = data.getPluginInfo().maxTrialDays
        val revenue = data.getCustomerRecurringRevenue()
        // concurrent requests of the same plugin share the values and their cache
        return entries.compute(data.pluginId) { _, cached ->
            cached?.takeIf { it.isValid() } ?: Entry(
                sales, trials, downloads, licenses, currency, today,
                when {
                    // the menu of a free plugin only offers the metrics, which it has
                    !data.pluginSummary.isPaidOrFreemium -> ChartMetricValues.freePlugin(downloads, data.exchangeRates, today, downloadsSource?.invoke(data.pluginId))
                    else -> ChartMetricValues.create(
                        sales ?: emptyList(),
                        licenses ?: emptyList(),
                        trials ?: emptyList(),
                        downloads,
                        data.exchangeRates,
                        today,
                        maxTrialDays,
                        revenue,
                        downloadsSource?.invoke(data.pluginId),
                    )
                },
            )
        }!!.values
    }

    companion object {
        /** The maximum number of metrics of a request. */
        const val MAX_METRICS = 32

        /** The JSON of the responses, properties without a value, e.g. without a moving average, are left out. */
        @OptIn(ExperimentalSerializationApi::class)
        val json: Json = Json { explicitNulls = false }

        /**
         * @return The metrics with values, e.g. to add them to the charts, ordered like [ChartMetric.all].
         */
        fun metrics(values: ChartMetricValues): List<ChartMetricData> {
            // the metrics with parameters are a single entry of their family, e.g. the downloads of a release
            val metrics = values.metrics
                .filter { it.family == null }
                .sortedBy { ChartMetric.all.indexOf(it) }
                .map { metric ->
                    ChartMetricData(
                        metric.id, metric.title, metric.unit.id(values.currency), metric.measure.name.lowercase(), metric.usesWindow,
                        metric.group, metric.description,
                    )
                }
            val families = ChartMetricFamily.all.filter { it in values.families }.map { family ->
                ChartMetricData(
                    family.id, family.title, family.unit.id(values.currency), family.measure.name.lowercase(), family.usesWindow,
                    family.group, family.description,
                    family.parameters.map { ChartParameterData(it.id, it.title, it.allLabel) },
                )
            }
            // the families follow the metrics of their group, e.g. the downloads of a release follow the downloads
            val groups = (metrics + families).map { it.group }.distinct()
            return (metrics + families).sortedBy { groups.indexOf(it.group) }
        }

        /**
         * Loads the data of the metrics, which aren't calculated from the data of the plugin, see
         * [ChartMetricValues.prepare].
         *
         * @param metrics The IDs of the metrics and of their moving averages, see [chartData].
         * @param from The first shown date, e.g. of the range of a chart, `null` for all dates, see [loadStart].
         */
        suspend fun prepare(values: ChartMetricValues, metrics: List<String>, from: YearMonthDay? = null, window: ChartWindow = ChartWindow.Day) {
            val months = metrics.mapNotNull { averageMonths[it.substringAfter(AVERAGE_SEPARATOR, "")] }.maxOrNull() ?: 0
            values.prepare(metrics.mapNotNull { ChartMetric.byId(it.substringBefore(AVERAGE_SEPARATOR)) }, loadStart(from, listOf(window), months))
        }

        /**
         * @param from The first shown date, `null` for all dates.
         * @param windows The windows of the values.
         * @param months The months before the dates, which the values need, e.g. of a moving average or the previous year.
         * @return The first day, whose data the values of the dates from [from] need: the start of the window, which
         *  ends at the first day of the months before [from], with months also of its rolling window, which the moving
         *  averages use, see [ChartMetricValues.average].
         */
        fun loadStart(from: YearMonthDay?, windows: Collection<ChartWindow>, months: Int): YearMonthDay? {
            from ?: return null
            val day = if (months > 0) ChartWindow.monthsBefore(from, months) else from
            return windows.flatMap { listOfNotNull(it.start(day), it.rolling.start(day).takeIf { months > 0 }) }.minOrNull() ?: day
        }

        /**
         * @return The values of the parameter of a family of metrics, e.g. the releases of the downloads of a release,
         *  `null` for an unknown family or parameter.
         */
        suspend fun parameterValues(values: ChartMetricValues, familyId: String, parameterId: String): List<ChartParameterValueData>? {
            val family = ChartMetricFamily.byId(familyId) ?: return null
            val index = family.parameters.indexOfFirst { it.id == parameterId }.takeIf { it >= 0 } ?: return null
            return values.parameterValues(family, index).map { ChartParameterValueData(it.value, it.label) }
        }

        /** The separator of the ID of a metric and the months of its moving average, e.g. `sales@avg3`. */
        const val AVERAGE_SEPARATOR = "@avg"

        /** The months of the moving averages by their text in the IDs, e.g. `3` of `sales@avg3`. */
        private val averageMonths = ChartMetricValues.AVERAGE_MONTHS.associateBy(Int::toString)

        /**
         * Unknown metrics, metrics without values and averages, which don't apply, are left out.
         * The moving average of a metric over the months before each day has the ID of the metric with the months,
         * e.g. `sales@avg3`, see [ChartMetricValues.average].
         *
         * @param metrics The IDs of the metrics, see [ChartMetric.id], and of their moving averages.
         * @return The values of the metrics at the dates of the [detail] level.
         */
        fun chartData(values: ChartMetricValues, metrics: List<String>, window: ChartWindow, detail: ChartDetail): ChartDataResponse {
            val days = values.days
            val indices = detail.indices(days)
            val series = metrics.distinct().mapNotNull { id ->
                val metric = ChartMetric.byId(id.substringBefore(AVERAGE_SEPARATOR)) ?: return@mapNotNull null
                val months = when {
                    AVERAGE_SEPARATOR in id -> averageMonths[id.substringAfter(AVERAGE_SEPARATOR)] ?: return@mapNotNull null
                    else -> null
                }
                val dailySeries = when (months) {
                    null -> values.series(metric, window)
                    else -> values.average(metric, window, months)
                } ?: return@mapNotNull null
                id to ChartSeriesData(
                    title = metric.title,
                    unit = dailySeries.unit,
                    measure = metric.measure.name.lowercase(),
                    usesWindow = metric.usesWindow,
                    averageMonths = months,
                    values = indices.map { dailySeries.values[it] },
                    incompleteFrom = indices.indexOfFirst { it >= dailySeries.incompleteFrom }.takeIf { it >= 0 } ?: indices.size,
                    partialUntil = indices.count { it < dailySeries.partialUntil },
                    fractionDigits = metric.fractionDigits,
                )
            }
            return ChartDataResponse(indices.map { days[it].asIsoString }, series.toMap())
        }
    }
}

/**
 * The response of `/chart-data`, e.g. `{"dates": ["2026-01-31", ...], "series": {"mrr": {"title": "MRR", ...}}}`.
 *
 * @param dates The dates of the values, ISO dates.
 * @param series The values of each requested metric by its ID, e.g. `sales` or `sales@avg3`.
 */
@Serializable
class ChartDataResponse(val dates: List<String>, val series: Map<String, ChartSeriesData>)

/**
 * The values of a metric of `/chart-data`.
 *
 * @param measure `flow`, `stock` or `ratio`, see [dev.ja.marketplace.data.ChartMeasure].
 * @param averageMonths The months of a moving average, `null` for the values of the metric.
 * @param values The value at each date, `null` without data, e.g. the revenue retention of the first year.
 * @param incompleteFrom The index of the first value, which may still change.
 * @param partialUntil The index of the first value, which covers its whole window, e.g. after the first year of a
 *  plugin, which started in February.
 * @param fractionDigits The maximum number of decimals of the shown values, see [ChartMetric.fractionDigits]. The
 *  values aren't rounded.
 */
@Serializable
class ChartSeriesData(
    val title: String,
    val unit: String,
    val measure: String,
    val usesWindow: Boolean,
    val averageMonths: Int?,
    val values: List<@Serializable(JsonDecimalSerializer::class) BigDecimal?>,
    val incompleteFrom: Int,
    val partialUntil: Int,
    val fractionDigits: Int,
)

/**
 * A metric of `/chart-metrics`, which the charts can show, see [ChartMetric], or a family of metrics with parameters,
 * see [ChartMetricFamily]. The ID of a metric of a family is the ID of the family followed by the values of the
 * parameters, see `/chart-metric-parameters`.
 */
@Serializable
class ChartMetricData(
    val id: String,
    val title: String,
    val unit: String,
    val measure: String,
    val usesWindow: Boolean,
    val group: String,
    /** What the metric measures, the tooltip of the metric in the menu to add metrics. */
    val description: String,
    /** The parameters of a family, `null` for a metric. */
    val parameters: List<ChartParameterData>? = null,
)

/**
 * A parameter of a family of metrics, see [dev.ja.marketplace.data.chart.ChartMetricParameter].
 *
 * @param allLabel The label of the missing value of an optional parameter, e.g. "All products".
 */
@Serializable
class ChartParameterData(val id: String, val title: String, val allLabel: String? = null)

/** A value of a parameter of `/chart-metric-parameters`, e.g. the ID and the version of a release. */
@Serializable
class ChartParameterValueData(val value: String, val label: String)

/**
 * Writes decimals as JSON numbers with their scale, e.g. `12.50`, without a conversion to `Double`.
 */
private object JsonDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.ja.marketplace.JsonDecimal", PrimitiveKind.DOUBLE)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: BigDecimal) {
        when (encoder) {
            is JsonEncoder -> encoder.encodeJsonElement(JsonUnquotedLiteral(value.toPlainString()))
            else -> encoder.encodeString(value.toPlainString())
        }
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        return when (decoder) {
            is JsonDecoder -> decoder.decodeJsonElement().jsonPrimitive.content.toBigDecimal()
            else -> decoder.decodeString().toBigDecimal()
        }
    }
}
