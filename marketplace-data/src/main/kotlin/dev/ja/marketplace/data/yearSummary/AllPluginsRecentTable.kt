/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import dev.ja.marketplace.exchangeRate.ExchangeRates
import java.time.DayOfWeek
import javax.money.MonetaryAmount

/**
 * The sales of each paid plugin of the account today, yesterday, this week (from Monday) and this month, with the
 * totals of all plugins. The data of the plugins is added with [add], it doesn't use the data of a single plugin.
 */
class AllPluginsRecentTable(
    private val displayExchangeRates: ExchangeRates,
    today: YearMonthDay = YearMonthDay.now(),
    title: String = "Recent Sales",
) : SimpleDataTable(title, "all-plugins-recent", "table-column-wide") {
    private val periods: List<Pair<DataTableColumn, YearMonthDayRange>> = listOf(
        DataTableColumn("today", "Today", "num") to YearMonthDayRange.of(today),
        DataTableColumn("yesterday", "Yesterday", "num") to YearMonthDayRange.of(today.add(0, 0, -1)),
        DataTableColumn("week", "This week", "num") to (startOfWeek(today) rangeTo today),
        DataTableColumn("month", "This month", "num") to (today.atFirstOfMonth() rangeTo today),
    )
    private val columnPlugin = DataTableColumn("plugin", null, accessibleTitle = "Plugin", preSorted = AriaSortOrder.Ascending)

    private val totalSales = periods.map { MonetaryAmountTracker(displayExchangeRates) }
    private val plugins = mutableListOf<PluginSales>()

    override val columns: List<DataTableColumn> = listOf(columnPlugin) + periods.map { it.first }

    /**
     * Adds the sales of a plugin, only the sales of the periods are used.
     */
    fun add(pluginName: String, sales: List<PluginSale>) {
        val pluginSales = periods.map { MonetaryAmountTracker(displayExchangeRates) }
        for ((index, period) in periods.withIndex()) {
            for (sale in sales) {
                if (sale.date in period.second) {
                    pluginSales[index].add(sale.date, sale.amountUSD, sale.amount)
                    totalSales[index].add(sale.date, sale.amountUSD, sale.amount)
                }
            }
        }

        plugins += PluginSales(pluginName, pluginSales.map { it.getTotalAmount() })
    }

    override suspend fun init(data: PluginData) {
        throw UnsupportedOperationException("The sales of all plugins are added with add()")
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
    }

    override suspend fun createSections(): List<DataTableSection> {
        val rows = plugins
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { plugin ->
                SimpleDateTableRow(mapOf(columnPlugin to plugin.name) + periods.mapIndexed { index, period -> period.first to plugin.sales[index] })
            }
        val footer = SimpleDateTableRow(periods.mapIndexed { index, period -> period.first to totalSales[index].getTotalAmount() }.toMap())
        return listOf(SimpleTableSection(rows = rows, footer = SimpleRowGroup(footer)))
    }

    private class PluginSales(val name: String, val sales: List<MonetaryAmount>)

    private companion object {
        fun startOfWeek(day: YearMonthDay): YearMonthDay {
            return day.add(0, 0, -(day.toLocalDate().dayOfWeek.value - DayOfWeek.MONDAY.value))
        }
    }
}
