/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.PluginData
import dev.ja.marketplace.data.overview.OverviewDailyValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The values of the overview table for each day or week as JSON for the charts, served at `/chart-series`.
 *
 * The values are calculated once for the loaded data of a plugin. The client caches the loaded data, so the values are
 * calculated again when the data is reloaded or on the next day.
 */
class DailyChartSeries {
    enum class Granularity {
        Day,
        Week,
    }

    private class Entry(
        val sales: Any?,
        val trials: Any?,
        val downloads: Any?,
        val currency: String,
        val today: YearMonthDay,
        val values: OverviewDailyValues,
    )

    private val entries = ConcurrentHashMap<PluginId, Entry>()

    suspend fun json(data: PluginData, granularity: Granularity): String {
        return toJson(values(data), granularity)
    }

    private suspend fun values(data: PluginData): OverviewDailyValues {
        val sales = data.getSales()
        val trials = data.getTrials()
        val downloads = data.getDownloadsDaily()
        val currency = data.exchangeRates.targetCurrency.currencyCode
        val today = YearMonthDay.now()

        val cached = entries[data.pluginId]
        if (cached != null && cached.sales === sales && cached.trials === trials && cached.downloads === downloads
            && cached.currency == currency && cached.today == today
        ) {
            return cached.values
        }

        val values = withContext(Dispatchers.Default) {
            OverviewDailyValues.create(
                sales ?: emptyList(),
                data.getLicenses() ?: emptyList(),
                trials ?: emptyList(),
                downloads,
                data.exchangeRates,
                today,
            )
        }
        entries[data.pluginId] = Entry(sales, trials, downloads, currency, today, values)
        return values
    }

    /**
     * Example: `{"dates": ["2026-01-01", ...], "series": {"mrr": {"unit": "USD", "values": [12.50, ...], "incompleteFrom": 42}}}`.
     * Values at the index `incompleteFrom` and later may still change.
     */
    private fun toJson(values: OverviewDailyValues, granularity: Granularity): String {
        val indices = when (granularity) {
            Granularity.Day -> values.days.indices.toList()
            Granularity.Week -> values.weekEndIndices()
        }

        return buildString {
            append("""{"dates":[""")
            indices.joinTo(this, ",") { "\"${values.days[it].asIsoString}\"" }
            append("""],"series":{""")
            values.series.entries.joinTo(this, ",") { (column, series) ->
                val incompleteFrom = indices.indexOfFirst { it >= series.incompleteFrom }.takeIf { it >= 0 } ?: indices.size
                val seriesValues = indices.joinToString(",") { series.values[it].toPlainString() }
                """"${column.id}":{"unit":"${series.unit}","values":[$seriesValues],"incompleteFrom":$incompleteFrom}"""
            }
            append("}}")
        }
    }
}
