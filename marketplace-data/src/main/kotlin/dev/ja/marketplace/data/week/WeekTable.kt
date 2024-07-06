/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.week

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.Currency
import dev.ja.marketplace.data.*
import java.math.BigDecimal
import java.util.*

class WeekTable(title: String = "This week") : SimpleDataTable(title, cssClass = "small table-striped"),
    MarketplaceDataSink {

    private data class WeekData(
        var sales: Amount,
        var downloads: Long,
        var trials: Int,
    )

    private val columnDay = DataTableColumn("day", null)
    private val columnSales = DataTableColumn("total", "Sales", "num")
    private val columnDownloads = DataTableColumn("total", "↓", "num", tooltip = "Downloads")
    private val columnTrials = DataTableColumn("total", "Trials", "num")

    private val dateRange = YearMonthDayRange.currentWeek()
    private val data = TreeMap<YearMonthDay, WeekData>()

    override val columns: List<DataTableColumn> = listOf(columnDay, columnSales, columnDownloads, columnTrials)

    override suspend fun init(data: PluginData) {
        dateRange.days().forEach { day ->
            val downloads = data.downloadsDaily.firstOrNull { it.day == day }?.downloads ?: 0
            val trials = data.trials?.filter { it.date == day }?.size ?: 0
            this.data[day] = WeekData(BigDecimal.ZERO, downloads, trials)
        }
    }

    override fun process(licenseInfo: LicenseInfo) {
        if (licenseInfo.sale.date in dateRange) {
            data.compute(licenseInfo.sale.date) { _, current ->
                current!!.also {
                    it.sales += licenseInfo.amountUSD
                }
            }
        }
    }

    override fun createSections(): List<DataTableSection> {
        val now = YearMonthDay.now()
        val rows = data.entries.map { (date, weekData) ->
            SimpleDateTableRow(
                mapOf(
                    columnDay to date,
                    columnSales to weekData.sales.withCurrency(Currency.USD),
                    columnDownloads to if (date < now) weekData.downloads.toBigInteger() else "—",
                    columnTrials to if (date <= now) weekData.trials.toBigInteger() else "—",
                ),
                cssClass = when {
                    date == now -> "today"
                    date > now -> "future"
                    else -> null
                }
            )
        }

        return listOf(
            SimpleTableSection(
                rows, footer = SimpleTableSection(
                    SimpleDateTableRow(
                        columnSales to data.values.sumOf { it.sales }.withCurrency(Currency.USD),
                        columnDownloads to data.values.sumOf { it.downloads },
                        columnTrials to data.values.sumOf { it.trials },
                    )
                )
            )
        )
    }
}