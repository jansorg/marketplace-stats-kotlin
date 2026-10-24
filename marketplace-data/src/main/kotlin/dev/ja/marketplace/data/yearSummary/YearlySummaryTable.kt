/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.MonthlyDownload
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.ratings.RatingHistory
import dev.ja.marketplace.data.trackers.*
import java.util.*

class YearlySummaryTable : SimpleDataTable("Years", "years", "table-column-wide") {
    private var maxTrialDays: Int = Marketplace.MAX_TRIAL_DAYS_DEFAULT
    private lateinit var downloads: List<MonthlyDownload>
    private lateinit var ratingHistory: RatingHistory

    private val trialsTracker: TrialTracker = SimpleTrialTracker()
    private val data = TreeMap<Int, YearSummary>(Comparator.reverseOrder())
    private lateinit var totalSales: PaymentAmountTracker

    private val columnYear = DataTableColumn("year", null)
    private val columnSalesTotal = DataTableColumn("sales", "Sales Total", "num")
    private val columnSalesFees = DataTableColumn("fees", "Fees", "num")
    private val columnSalesPaid = DataTableColumn("paid", "Paid", "num")
    private val columnARR = DataTableColumn("arr", "ARR", "num")
    private val columnDownloads = DataTableColumn("downloads", "↓", "num", chartTitle = "Downloads", tooltip = "Downloads")
    private val columnRating = DataTableColumn(
        "rating", "Rating", "num", tooltip = RatingHistory.TOOLTIP,
        chartMeasure = ChartMeasure.Stock, dailyChartSeries = RatingHistory.DAILY_SERIES_ID,
    )
    private val columnTrials = DataTableColumn("trials", "Trials", "num")
    private val columnTrialsConverted = DataTableColumn("trials-converted", "Conv. Trials", "num")

    private data class YearSummary(
        val sales: PaymentAmountTracker,
        val annualRevenue: AnnualRecurringRevenueTracker,
    )

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.maxTrialDays = data.getPluginInfo().purchaseInfo?.trialPeriod ?: Marketplace.MAX_TRIAL_DAYS_DEFAULT
        this.downloads = data.getDownloadsMonthly()
        this.ratingHistory = data.getRatingHistory()
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
            columnRating,
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
                        // the rating at the end of the year, of the current year at today, which may still change
                        columnRating to ratingHistory.ratingAt(minOf(YearMonthDay(year, 12, 31), now))
                            ?.let { if (year == now.year) IncompleteValue(RatingValue(it)) else RatingValue(it) },
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