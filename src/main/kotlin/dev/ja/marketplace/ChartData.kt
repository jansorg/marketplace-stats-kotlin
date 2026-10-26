/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.data.PluginData
import dev.ja.marketplace.data.chart.ChartDetail
import dev.ja.marketplace.data.chart.ChartMetric
import dev.ja.marketplace.data.chart.ChartMetricValues
import dev.ja.marketplace.data.chart.ChartWindow
import dev.ja.marketplace.data.trackers.maxTrialDays
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * The values of the [ChartMetric]s of the plugins as JSON for the charts, served at `/chart-data`.
 *
 * The values of a plugin are calculated for the loaded data of the plugin, the values of a metric and a window when
 * they're requested first. The client caches the loaded data, so the values are calculated again when the data is
 * reloaded or on the next day.
 */
class ChartData {
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
                ChartMetricValues.create(
                    sales ?: emptyList(),
                    licenses ?: emptyList(),
                    trials ?: emptyList(),
                    downloads,
                    data.exchangeRates,
                    today,
                    maxTrialDays,
                    revenue,
                ),
            )
        }!!.values
    }

    companion object {
        /** The maximum number of metrics of a request. */
        const val MAX_METRICS = 32

        /**
         * Example: `[{"id": "sales", "title": "Sales", "unit": "USD", "measure": "flow", "usesWindow": true, "group": "Sales"}, ...]`.
         *
         * @return The metrics with values, e.g. to add them to the charts, ordered like [ChartMetric.all].
         */
        fun metricsJson(values: ChartMetricValues): String {
            // the shares of unknown customer types are no row of the table
            val metrics = values.metrics.filterNot { it.id.endsWith(".${CustomerType.Unknown}") }
            return metrics.sortedBy { ChartMetric.all.indexOf(it) }.joinToString(",", "[", "]") { metric ->
                "{\"id\":${JsonPrimitive(metric.id)},\"title\":${JsonPrimitive(metric.title)}," +
                        "\"unit\":${JsonPrimitive(metric.unit.id(values.currency))},\"measure\":\"${metric.measure.name.lowercase()}\"," +
                        "\"usesWindow\":${metric.usesWindow},\"group\":${JsonPrimitive(metric.group)}}"
            }
        }

        /** The separator of the ID of a metric and the months of its moving average, e.g. `sales@avg3`. */
        const val AVERAGE_SEPARATOR = "@avg"

        /** The months of the moving averages by their text in the IDs, e.g. `3` of `sales@avg3`. */
        private val averageMonths = ChartMetricValues.AVERAGE_MONTHS.associateBy(Int::toString)

        /**
         * Example: `{"dates": ["2026-01-31", ...], "series": {"mrr": {"title": "MRR", "unit": "USD", "measure": "stock",
         * "usesWindow": false, "values": [12.50, ...], "incompleteFrom": 42}}}`.
         * Values at the index `incompleteFrom` and later may still change, values before the index `partialUntil` only cover a
         * part of their window, e.g. the first year of a plugin, which started in February. Values without data are `null`.
         * The moving average of a metric over the months before each day has the ID of the metric with the months,
         * e.g. `sales@avg3`, and the property `averageMonths`, see [ChartMetricValues.average].
         * Unknown metrics, metrics without values and averages, which don't apply, are left out.
         *
         * @param metrics The IDs of the metrics, see [ChartMetric.id], and of their moving averages.
         */
        fun json(values: ChartMetricValues, metrics: List<String>, window: ChartWindow, detail: ChartDetail): String {
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
                }
                dailySeries?.let { Triple(id, metric, months) to it }
            }
            return buildString {
                append("""{"dates":[""")
                indices.joinTo(this, ",") { "\"${days[it].asIsoString}\"" }
                append("""],"series":{""")
                series.joinTo(this, ",") { (key, values) ->
                    val (id, metric, months) = key
                    val incompleteFrom = indices.indexOfFirst { it >= values.incompleteFrom }.takeIf { it >= 0 } ?: indices.size
                    val partialUntil = indices.count { it < values.partialUntil }
                    val seriesValues = indices.joinToString(",") { values.values[it]?.toPlainString() ?: "null" }
                    val measure = metric.measure.name.lowercase()
                    val average = if (months != null) ",\"averageMonths\":$months" else ""
                    "${JsonPrimitive(id)}:{\"title\":${JsonPrimitive(metric.title)},\"unit\":${JsonPrimitive(values.unit)}," +
                            "\"measure\":\"$measure\",\"usesWindow\":${metric.usesWindow}$average," +
                            "\"values\":[$seriesValues],\"incompleteFrom\":$incompleteFrom,\"partialUntil\":$partialUntil}"
                }
                append("}}")
            }
        }
    }
}
