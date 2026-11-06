/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import dev.ja.marketplace.exchangeRate.ExchangeRates
import java.time.DayOfWeek
import javax.money.MonetaryAmount

/**
 * The sales and trials of each paid plugin of the account today, yesterday, this week (from Monday) and this month,
 * with the totals of all plugins. The table has a section of the sales and a section of the trials.
 * The data of the plugins is added with [add], it doesn't use the data of a single plugin.
 */
class AllPluginsRecentTable(
    private val displayExchangeRates: ExchangeRates,
    today: YearMonthDay = YearMonthDay.now(),
    title: String = "Recent Sales and Trials",
) : SimpleDataTable(title, "all-plugins-recent", "table-column-wide") {
    private val periods: List<Pair<DataTableColumn, YearMonthDayRange>> = listOf(
        DataTableColumn("today", "Today", "num") to YearMonthDayRange.of(today),
        DataTableColumn("yesterday", "Yesterday", "num") to YearMonthDayRange.of(today.add(0, 0, -1)),
        DataTableColumn("week", "This week", "num") to (startOfWeek(today) rangeTo today),
        DataTableColumn("month", "This month", "num") to (today.atFirstOfMonth() rangeTo today),
    )
    private val columnPlugin = DataTableColumn("plugin", null, accessibleTitle = "Plugin", preSorted = AriaSortOrder.Ascending)

    private val totalSales = periods.map { MonetaryAmountTracker(displayExchangeRates) }
    private val totalTrials = IntArray(periods.size)
    private val plugins = mutableListOf<PluginPeriods>()

    override val columns: List<DataTableColumn> = listOf(columnPlugin) + periods.map { it.first }

    /**
     * Adds the sales and the trials of a plugin, only the data of the periods is used.
     */
    fun add(pluginName: String, sales: List<PluginSale>, trials: List<PluginTrial>) {
        val pluginSales = periods.map { MonetaryAmountTracker(displayExchangeRates) }
        val pluginTrials = IntArray(periods.size)
        for ((index, period) in periods.withIndex()) {
            val range = period.second
            for (sale in sales) {
                if (sale.date in range) {
                    pluginSales[index].add(sale.date, sale.amountUSD, sale.amount)
                    totalSales[index].add(sale.date, sale.amountUSD, sale.amount)
                }
            }

            pluginTrials[index] = trials.count { it.date in range }
            totalTrials[index] += pluginTrials[index]
        }

        plugins += PluginPeriods(pluginName, pluginSales.map { it.getTotalAmount() }, pluginTrials)
    }

    override suspend fun init(data: PluginData) {
        throw UnsupportedOperationException("The sales of all plugins are added with add()")
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
    }

    override suspend fun createSections(): List<DataTableSection> {
        val sortedPlugins = plugins.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return listOf(
            section("Sales", sortedPlugins, totalSales.map { it.getTotalAmount() }) { it.sales },
            section("Trials", sortedPlugins, totalTrials.map { it.toBigInteger() }) { plugin -> plugin.trials.map { it.toBigInteger() } },
        )
    }

    private fun section(
        title: String,
        plugins: List<PluginPeriods>,
        totals: List<Any>,
        values: (PluginPeriods) -> List<Any>,
    ): DataTableSection {
        val rows = plugins.map { plugin ->
            val pluginValues = values(plugin)
            SimpleDateTableRow(mapOf(columnPlugin to plugin.name) + periods.mapIndexed { index, period -> period.first to pluginValues[index] })
        }
        val footer = SimpleDateTableRow(periods.mapIndexed { index, period -> period.first to totals[index] }.toMap())
        return SimpleTableSection(rows = rows, title = title, footer = SimpleRowGroup(footer))
    }

    private class PluginPeriods(val name: String, val sales: List<MonetaryAmount>, val trials: IntArray)

    private companion object {
        fun startOfWeek(day: YearMonthDay): YearMonthDay {
            return day.add(0, 0, -(day.toLocalDate().dayOfWeek.value - DayOfWeek.MONDAY.value))
        }
    }
}
