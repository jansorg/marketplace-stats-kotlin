/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.MonthlyDownload
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.overview.OverviewDailyColumn
import dev.ja.marketplace.data.trackers.*
import java.util.*

class YearlySummaryTable : SimpleDataTable("Years", "years", "table-column-wide") {
    private var maxTrialDays: Int = Marketplace.MAX_TRIAL_DAYS_DEFAULT
    private lateinit var downloads: List<MonthlyDownload>

    private val trialsTracker: TrialTracker = SimpleTrialTracker()
    private val data = TreeMap<Int, YearSummary>(Comparator.reverseOrder())
    private lateinit var totalSales: PaymentAmountTracker

    private val columnYear = DataTableColumn("year", null)
    // the charts show the values per week and per day, the sums of the year before each day, see AllPluginsDailyValues
    private val columnSalesTotal = DataTableColumn("sales", "Sales Total", "num", chartMeasure = ChartMeasure.Flow, dailyChartSeries = AllPluginsDailyValues.SALES)
    private val columnSalesFees = DataTableColumn("fees", "Fees", "num", chartMeasure = ChartMeasure.Flow, dailyChartSeries = AllPluginsDailyValues.FEES)
    private val columnSalesPaid = DataTableColumn("paid", "Paid", "num", chartMeasure = ChartMeasure.Flow, dailyChartSeries = AllPluginsDailyValues.PAID)
    private val columnARR = DataTableColumn("arr", "ARR", "num", chartMeasure = ChartMeasure.Stock, dailyChartSeries = OverviewDailyColumn.Arr.id)
    private val columnDownloads = DataTableColumn(
        "downloads", "↓", "num", chartTitle = "Downloads", tooltip = "Downloads",
        chartMeasure = ChartMeasure.Flow, dailyChartSeries = AllPluginsDailyValues.DOWNLOADS,
    )
    private val columnTrials = DataTableColumn("trials", "Trials", "num", chartMeasure = ChartMeasure.Flow, dailyChartSeries = AllPluginsDailyValues.TRIALS)
    private val columnTrialsConverted = DataTableColumn("trials-converted", "Conv. Trials", "num")

    private data class YearSummary(
        val sales: PaymentAmountTracker,
        val annualRevenue: AnnualRecurringRevenueTracker,
    )

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.maxTrialDays = data.getPluginInfo().purchaseInfo?.trialPeriod ?: Marketplace.MAX_TRIAL_DAYS_DEFAULT
        this.downloads = data.getDownloadsMonthly()
        this.totalSales = PaymentAmountTracker(YearMonthDayRange.MAX, data.exchangeRates)

        val now = YearMonthDay.now()
        for (year in Marketplace.Birthday.year..now.year) {
            val yearRange = YearMonthDayRange.ofYear(year)
            this.data[year] = YearSummary(
                PaymentAmountTracker(yearRange, data.exchangeRates),
                AnnualRecurringRevenueTracker(yearRange, data.exchangeRates)
            )
        }

        trialsTracker.init(data.getTrials() ?: emptyList())
    }

    override val isChartable: Boolean = true
    override val chartPeriod: ChartPeriod = ChartPeriod.Year

    override val columns: List<DataTableColumn> by lazy {
        listOfNotNull(
            columnYear,
            columnSalesTotal,
            columnSalesFees,
            columnSalesPaid,
            columnARR,
            columnDownloads,
            columnTrials.takeIf { maxTrialDays > 0 },
            columnTrialsConverted.takeIf { maxTrialDays > 0 },
        )
    }

    override suspend fun process(sale: PluginSale) {
        trialsTracker.processSale(sale)

        totalSales.add(sale.date, sale.amountUSD, sale.amount)

        val yearData = data[sale.date.year]!!
        yearData.sales.add(sale.date, sale.amountUSD, sale.amount)
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        // a license has to be processes by every year's arr tracker because of the continuity discount
        data.values.forEach { yearData ->
            yearData.annualRevenue.processLicenseSale(licenseInfo)
        }
    }

    override suspend fun createSections(): List<DataTableSection> {
        val now = YearMonthDay.now()
        val allTrialsAnyDuration = trialsTracker.getResult(YearMonthDayRange.MAX)
        val allTrialsTrialDuration = trialsTracker.getResultWithinConversionPeriod(YearMonthDayRange.MAX, maxTrialDays)

        val rows = data.entries.toList()
            .dropLastWhile { it.value.sales.isZero }
            .map { (year, yearData) ->
                val yearDateRange = YearMonthDayRange.ofYear(year)
                val trialResultAnyDuration = trialsTracker.getResult(yearDateRange)
                val trialResultTrialDuration = trialsTracker.getResultWithinConversionPeriod(yearDateRange, maxTrialDays)
                // The recurring revenue of the current year is incomplete,
                // because licenses renewed later in the year are not included yet
                val arrValue = yearData.annualRevenue.getResult().amounts.getTotalAmount()
                    .let { if (year == now.year) IncompleteValue(it) else it }
                SimpleDateTableRow(
                    values = mapOf(
                        columnYear to year,
                        columnSalesTotal to yearData.sales.totalAmount,
                        columnSalesFees to yearData.sales.feesAmount,
                        columnSalesPaid to yearData.sales.paidAmount,
                        columnARR to arrValue,
                        columnDownloads to downloads
                            .filter { it.firstOfMonth.year == year }
                            .sumOf(MonthlyDownload::downloads)
                            .toBigInteger(),
                        columnTrials to (trialResultAnyDuration.totalTrials.takeIf { it > 0 }?.toBigInteger() ?: NoValue),
                        columnTrialsConverted to trialResultTrialDuration.displayedConversionRate,
                    ),
                    tooltips = mapOf(
                        columnARR to IncompleteRecurringRevenueTooltip.takeIf { year == now.year },
                        columnTrialsConverted to trialResultAnyDuration.getTooltipConverted() +
                                "\n" +
                                trialResultTrialDuration.getTooltipConverted(trialConversionDays(maxTrialDays))
                    ),
                    cssClass = when {
                        year == now.year -> "today"
                        else -> null
                    }
                )
            }

        return listOf(
            SimpleTableSection(
                rows = rows,
                footer = SimpleTableSection(
                    SimpleDateTableRow(
                        values = mapOf(
                            columnSalesTotal to totalSales.totalAmount,
                            columnSalesFees to totalSales.feesAmount,
                            columnSalesPaid to totalSales.paidAmount,
                            columnDownloads to downloads.sumOf { it.downloads.toBigInteger() },
                            columnTrials to allTrialsAnyDuration.totalTrials.toBigInteger(),
                            columnTrialsConverted to allTrialsTrialDuration.convertedTrialsPercentage,
                        ),
                        tooltips = mapOf(
                            columnTrialsConverted to allTrialsAnyDuration.getTooltipConverted() +
                                    "\n" +
                                    allTrialsTrialDuration.getTooltipConverted(trialConversionDays(maxTrialDays)),
                        )
                    )
                )
            )
        )
    }
}