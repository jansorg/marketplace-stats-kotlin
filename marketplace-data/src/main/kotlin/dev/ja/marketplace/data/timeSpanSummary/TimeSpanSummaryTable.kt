/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.timeSpanSummary

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.chart.ChartMetric
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import dev.ja.marketplace.data.trackers.maxTrialDays
import java.util.*

/**
 * Sales, downloads and trials per day of [dateRange].
 */
class TimeSpanSummaryTable(
    private val dateRange: YearMonthDayRange,
    title: String,
) : SimpleDataTable(title, cssClass = "small table-striped"), MarketplaceDataSink {
    /** @param maxDays The number of shown days, today and the days before it. */
    constructor(maxDays: Int, title: String) : this(
        YearMonthDay.now().let { YearMonthDayRange(it.add(0, 0, -(maxDays - 1)), it) },
        title
    )

    private data class DaySummary(
        var sales: MonetaryAmountTracker,
        var downloads: Long,
        var trials: Int,
    )

    private val columnDay = DataTableColumn("day", null)
    private val columnSales = DataTableColumn("total", "Sales", "num", chartMetric = ChartMetric.Sales.id)
    private val columnDownloads = DataTableColumn("total", "↓", "num col-symbol", chartTitle = "Downloads", tooltip = "Downloads", chartMetric = ChartMetric.Downloads.id)
    private val columnTrials = DataTableColumn("total", "Trials", "num", chartMetric = ChartMetric.Trials.id)

    private var maxTrialDays: Int = Marketplace.MAX_TRIAL_DAYS_DEFAULT
    private val daySummaries = TreeMap<YearMonthDay, DaySummary>()
    private lateinit var totalSales: MonetaryAmountTracker

    override val isChartable: Boolean = true
    override val chartDateRange: YearMonthDayRange = dateRange

    override val columns: List<DataTableColumn> by lazy {
        listOfNotNull(
            columnDay,
            columnSales,
            columnDownloads,
            columnTrials.takeIf { maxTrialDays > 0 }
        )
    }

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.totalSales = MonetaryAmountTracker(exchangeRates)
        this.maxTrialDays = data.getPluginInfo().maxTrialDays

        val downloadsByDay = data.getDownloadsDaily().associate { it.day to it.downloads }
        val trialsByDay = data.getTrials()?.groupingBy { it.date }?.eachCount() ?: emptyMap()

        dateRange.days().forEach { day ->
            val downloads = downloadsByDay[day] ?: 0
            val trials = trialsByDay[day] ?: 0
            this.daySummaries[day] = DaySummary(MonetaryAmountTracker(data.exchangeRates), downloads, trials)
        }
    }

    // the amounts of the sales, like the other tables: the amounts of the licenses of a changed sale may be higher
    override suspend fun process(sale: PluginSale) {
        if (sale.date in dateRange) {
            totalSales.add(sale.date, sale.amountUSD, sale.amount)
            daySummaries.getValue(sale.date).sales.add(sale.date, sale.amountUSD, sale.amount)
        }
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        // the sales are processed
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