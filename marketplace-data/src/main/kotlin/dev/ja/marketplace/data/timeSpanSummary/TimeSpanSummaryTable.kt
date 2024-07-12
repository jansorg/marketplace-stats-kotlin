/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.timeSpanSummary

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import java.util.*

class TimeSpanSummaryTable(maxDays: Int, title: String) : SimpleDataTable(title, cssClass = "small table-striped"),
    MarketplaceDataSink {

    private data class DaySummary(
        var sales: MonetaryAmountTracker,
        var downloads: Long,
        var trials: Int,
    )

    private val columnDay = DataTableColumn("day", null)
    private val columnSales = DataTableColumn("total", "Sales", "num")
    private val columnDownloads = DataTableColumn("total", "↓", "num", tooltip = "Downloads")
    private val columnTrials = DataTableColumn("total", "Trials", "num")

    private val dateRange = YearMonthDay.now().let { YearMonthDayRange(it.add(0, 0, -maxDays), it) }
    private val daySummaries = TreeMap<YearMonthDay, DaySummary>()
    private lateinit var totalSales: MonetaryAmountTracker

    override val columns: List<DataTableColumn> = listOf(columnDay, columnSales, columnDownloads, columnTrials)

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.totalSales = MonetaryAmountTracker(exchangeRates)

        dateRange.days().forEach { day ->
            val downloads = data.downloadsDaily.firstOrNull { it.day == day }?.downloads ?: 0
            val trials = data.trials?.filter { it.date == day }?.size ?: 0
            this.daySummaries[day] = DaySummary(MonetaryAmountTracker(data.exchangeRates), downloads, trials)
        }
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        if (licenseInfo.sale.date in dateRange) {
            totalSales.add(licenseInfo.sale.date, licenseInfo.amountUSD, licenseInfo.amount)

            daySummaries.compute(licenseInfo.sale.date) { _, current ->
                current!!.also {
                    it.sales.add(licenseInfo.sale.date, licenseInfo.amountUSD, licenseInfo.amount)
                }
            }
        }
    }

    override suspend fun createSections(): List<DataTableSection> {
        val now = YearMonthDay.now()
        val rows = daySummaries.entries.map { (date, weekData) ->
            SimpleDateTableRow(
                mapOf(
                    columnDay to date,
                    columnSales to weekData.sales.getTotalAmount(),
                    columnDownloads to if (date < now) weekData.downloads.toBigInteger() else NoValue,
                    columnTrials to if (date <= now) weekData.trials.toBigInteger() else NoValue,
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
                        columnSales to totalSales.getTotalAmount(),
                        columnDownloads to daySummaries.values.sumOf { it.downloads }.toBigInteger(),
                        columnTrials to daySummaries.values.sumOf { it.trials }.toBigInteger(),
                    )
                )
            )
        )
    }
}