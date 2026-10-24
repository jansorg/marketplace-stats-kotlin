/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.data.PluginData
import dev.ja.marketplace.data.overview.DailySeries
import dev.ja.marketplace.data.overview.OverviewDailyValues
import dev.ja.marketplace.data.ratings.RatingHistory
import dev.ja.marketplace.data.subscriptions.SubscriptionDailyValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.util.concurrent.ConcurrentHashMap

/**
 * The values of the overview table and of the monthly tables of the subscription metrics for each day or week as JSON
 * for the charts, served at `/chart-series`.
 *
 * The values are calculated once for the loaded data of a plugin. The client caches the loaded data, so the values are
 * calculated again when the data is reloaded or on the next day.
 */
class DailyChartSeries {
    enum class Granularity {
        Day,
        Week,
    }

    private class Values(
        val overview: OverviewDailyValues,
        /** The series of all columns by their ID, see [dev.ja.marketplace.data.DataTableColumn.dailyChartSeries]. */
        val series: Map<String, DailySeries>,
    )

    private class Entry(
        val sales: Any?,
        val trials: Any?,
        val downloads: Any?,
        val currency: String,
        val today: YearMonthDay,
        val values: Values,
    )

    private val entries = ConcurrentHashMap<PluginId, Entry>()

    suspend fun json(data: PluginData, granularity: Granularity): String {
        return toJson(values(data), granularity)
    }

    private suspend fun values(data: PluginData): Values {
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

        val licenses = data.getLicenses() ?: emptyList()
        val revenue = data.getCustomerRecurringRevenue()
        val ratingHistory = data.getRatingHistory()
        val values = withContext(Dispatchers.Default) {
            val overview = OverviewDailyValues.create(
                sales ?: emptyList(),
                licenses,
                trials ?: emptyList(),
                downloads,
                data.exchangeRates,
                today,
            )
            val subscriptions = SubscriptionDailyValues.create(overview.days, licenses, revenue, today)
            // the rating of today may still change
            val rating = DailySeries("rating", overview.days.map(ratingHistory::ratingAt), overview.days.lastIndex)
            Values(
                overview,
                overview.series.mapKeys { it.key.id } + subscriptions.mapKeys { it.key.id } + (RatingHistory.DAILY_SERIES_ID to rating),
            )
        }
        entries[data.pluginId] = Entry(sales, trials, downloads, currency, today, values)
        return values
    }

    private fun toJson(values: Values, granularity: Granularity): String {
        return toJson(values.overview.days, values.series, granularity)
    }

    /**
     * Example: `{"dates": ["2026-01-01", ...], "series": {"mrr": {"unit": "USD", "values": [12.50, ...], "incompleteFrom": 42}}}`.
     * Values at the index `incompleteFrom` and later may still change, values without data are `null`.
     *
     * @param days The days of the values of the series, without gaps.
     * @param series The values of each day by the ID of their column.
     */
    fun toJson(days: List<YearMonthDay>, series: Map<String, DailySeries>, granularity: Granularity): String {
        val indices = when (granularity) {
            Granularity.Day -> days.indices.toList()
            // the last day of each week, i.e. each Sunday, and today
            Granularity.Week -> days.indices.filter { index ->
                index == days.lastIndex || days[index].toLocalDate().dayOfWeek == DayOfWeek.SUNDAY
            }
        }

        return buildString {
            append("""{"dates":[""")
            indices.joinTo(this, ",") { "\"${days[it].asIsoString}\"" }
            append("""],"series":{""")
            series.entries.joinTo(this, ",") { (id, values) ->
                val incompleteFrom = indices.indexOfFirst { it >= values.incompleteFrom }.takeIf { it >= 0 } ?: indices.size
                val seriesValues = indices.joinToString(",") { values.values[it]?.toPlainString() ?: "null" }
                """"$id":{"unit":"${values.unit}","values":[$seriesValues],"incompleteFrom":$incompleteFrom}"""
            }
            append("}}")
        }
    }
}
