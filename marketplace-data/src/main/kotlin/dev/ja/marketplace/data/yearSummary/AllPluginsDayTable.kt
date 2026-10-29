/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import dev.ja.marketplace.exchangeRate.ExchangeRates
import javax.money.MonetaryAmount

/**
 * The sales and trials of each paid plugin of the account at a day, by default today, with the totals of all plugins.
 * The data of the plugins is added with [add], it doesn't use the data of a single plugin.
 */
class AllPluginsDayTable(
    private val displayExchangeRates: ExchangeRates,
    private val day: YearMonthDay = YearMonthDay.now(),
    title: String = "Today",
) : SimpleDataTable(title, "all-plugins-day", "small table-striped") {
    private val columnPlugin = DataTableColumn("plugin", null, accessibleTitle = "Plugin", preSorted = AriaSortOrder.Ascending)
    private val columnSales = DataTableColumn("sales", "Sales", "num")
    private val columnTrials = DataTableColumn("trials", "Trials", "num")

    private val totalSales = MonetaryAmountTracker(displayExchangeRates)
    private var totalTrials = 0
    private val plugins = mutableListOf<PluginDay>()

    override val columns: List<DataTableColumn> = listOf(columnPlugin, columnSales, columnTrials)

    /**
     * Adds the sales and the trials of a plugin, only the data of the day is used.
     */
    fun add(pluginName: String, sales: List<PluginSale>, trials: List<PluginTrial>) {
        val pluginSales = MonetaryAmountTracker(displayExchangeRates)
        for (sale in sales) {
            if (sale.date == day) {
                pluginSales.add(sale.date, sale.amountUSD, sale.amount)
                totalSales.add(sale.date, sale.amountUSD, sale.amount)
            }
        }

        val pluginTrials = trials.count { it.date == day }
        totalTrials += pluginTrials
        plugins += PluginDay(pluginName, pluginSales.getTotalAmount(), pluginTrials)
    }

    override suspend fun init(data: PluginData) {
        throw UnsupportedOperationException("The sales of all plugins are added with add()")
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
    }

    override suspend fun createSections(): List<DataTableSection> {
        val rows = plugins
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map {
                SimpleDateTableRow(
                    columnPlugin to it.name,
                    columnSales to it.sales,
                    columnTrials to it.trials.toBigInteger(),
                )
            }

        return listOf(
            SimpleTableSection(
                rows = rows,
                footer = SimpleRowGroup(
                    SimpleDateTableRow(
                        columnSales to totalSales.getTotalAmount(),
                        columnTrials to totalTrials.toBigInteger(),
                    )
                )
            )
        )
    }

    private class PluginDay(val name: String, val sales: MonetaryAmount, val trials: Int)
}
