/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.churn.COMBINED_CHURN_TOOLTIP
import dev.ja.marketplace.churn.ChurnProcessor
import dev.ja.marketplace.churn.ChurnValidities
import dev.ja.marketplace.churn.isCountedByChurn
import dev.ja.marketplace.churn.licenseChurnProcessor
import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.MonthlyDownload
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.chart.ChartMetric
import dev.ja.marketplace.data.trackers.*
import java.util.*

class YearlySummaryTable : SimpleDataTable("Years", "years", "table-column-wide") {
    private var pluginId: PluginId? = null

    /** Free plugins have no sales, licenses and trials, their table only has the downloads. */
    private var isPaidOrFreemium: Boolean = true
    private var maxTrialDays: Int = Marketplace.MAX_TRIAL_DAYS_DEFAULT
    private lateinit var churnValidities: ChurnValidities

    /** The first day with data of the plugin, the sums of the first year only cover a part of it. */
    private var pluginStart: YearMonthDay? = null
    private lateinit var downloads: List<MonthlyDownload>

    private val trialsTracker: TrialTracker = SimpleTrialTracker()
    private val data = TreeMap<Int, YearSummary>(Comparator.reverseOrder())
    private lateinit var totalSales: PaymentAmountTracker

    private val columnYear = DataTableColumn("year", null)
    private val columnSalesTotal = DataTableColumn("sales", "Sales Total", "num", chartMeasure = ChartMeasure.Flow, chartMetric = ChartMetric.Sales.id)
    private val columnSalesFees = DataTableColumn("fees", "Fees", "num", chartMeasure = ChartMeasure.Flow, chartMetric = ChartMetric.Fees.id)
    private val columnSalesPaid = DataTableColumn("paid", "Invoice", "num", chartMeasure = ChartMeasure.Flow, chartMetric = ChartMetric.Invoice.id)
    private val columnARR = DataTableColumn("arr", "ARR", "num", chartMeasure = ChartMeasure.Stock, chartMetric = ChartMetric.Arr.id)
    private val columnChurn = DataTableColumn(
        "churn-combined-paid", "Churn (all)", "num num-percentage", tooltip = COMBINED_CHURN_TOOLTIP,
        chartMeasure = ChartMeasure.Ratio, chartMetric = ChartMetric.ChurnCombined.id,
    )
    // the only column of the values of a free plugin has a title instead of the symbol
    private val columnDownloads by lazy {
        DataTableColumn(
            "downloads", if (isPaidOrFreemium) "↓" else "Downloads", if (isPaidOrFreemium) "num col-symbol" else "num",
            chartTitle = "Downloads", tooltip = "Downloads",
            chartMeasure = ChartMeasure.Flow, chartMetric = ChartMetric.Downloads.id,
        )
    }
    private val columnTrials = DataTableColumn("trials", "Trials", "num", chartMeasure = ChartMeasure.Flow, chartMetric = ChartMetric.Trials.id)
    // the values per day are the rates of the trials of the year before each day
    private val columnTrialsConverted = DataTableColumn(
        "trials-converted", "Conv. Trials", "num",
        chartMeasure = ChartMeasure.Ratio, chartMetric = ChartMetric.TrialConversion.id,
    )

    private class YearSummary(
        val sales: PaymentAmountTracker,
        val annualRevenue: AnnualRecurringRevenueTracker,
        /** The churn of the paid licenses of all periods within the year, until today in the current year. */
        val churn: ChurnProcessor<LicenseInfo>,
    )

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.pluginId = data.pluginId
        this.isPaidOrFreemium = data.pluginSummary.isPaidOrFreemium
        this.maxTrialDays = data.getPluginInfo().maxTrialDays
        this.churnValidities = ChurnValidities(data.getLicenses() ?: emptyList())
        this.pluginStart = data.getFirstActivityDate()
        this.downloads = data.getDownloadsMonthly()
        this.totalSales = PaymentAmountTracker(YearMonthDayRange.MAX, data.exchangeRates)

        val now = YearMonthDay.now()
        for (year in firstReportYear(downloads)..now.year) {
            val yearRange = YearMonthDayRange.ofYear(year)
            val untilToday = yearRange.copy(end = minOf(yearRange.end, now))
            this.data[year] = YearSummary(
                PaymentAmountTracker(yearRange, data.exchangeRates),
                // the recurring revenue of the current year at today
                AnnualRecurringRevenueTracker(untilToday, data.exchangeRates),
                // the churn of the current year until today, licenses expiring later in the year may still be renewed
                licenseChurnProcessor(untilToday),
            )
        }

        trialsTracker.init(data.getTrials() ?: emptyList())
    }

    override val isChartable: Boolean = true
    override val chartPeriod: ChartPeriod = ChartPeriod.Year

    override val columns: List<DataTableColumn> by lazy {
        when {
            isPaidOrFreemium -> listOfNotNull(
                columnYear,
                columnSalesTotal,
                columnSalesFees,
                columnSalesPaid,
                columnARR,
                columnChurn,
                columnDownloads,
                columnTrials.takeIf { maxTrialDays > 0 },
                columnTrialsConverted.takeIf { maxTrialDays > 0 },
            )

            else -> listOf(columnYear, columnDownloads)
        }
    }

    override suspend fun process(sale: PluginSale) {
        trialsTracker.processSale(sale)

        totalSales.add(sale.date, sale.amountUSD, sale.amount)

        val yearData = data[sale.date.year]!!
        yearData.sales.add(sale.date, sale.amountUSD, sale.amount)
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        // every year needs all licenses, the ARR of a year uses the licenses valid at its end
        val validity = churnValidities.validity(licenseInfo)
        val isCountedByChurn = licenseInfo.isCountedByChurn(null)
        data.values.forEach { yearData ->
            yearData.annualRevenue.processLicenseSale(licenseInfo)
            if (validity != null) {
                yearData.churn.processValue(licenseInfo, validity, isCountedByChurn)
            }
        }
    }

    override suspend fun createSections(): List<DataTableSection> {
        val now = YearMonthDay.now()
        val allTrialsAnyDuration = trialsTracker.getResult(YearMonthDayRange.MAX)
        val allTrialsTrialDuration = trialsTracker.getResultWithinConversionPeriod(YearMonthDayRange.MAX, maxTrialDays)

        val rows = data.entries.toList()
            // the years before the first sale, or before the first download of a free plugin
            .dropLastWhile { (year, yearData) ->
                when {
                    isPaidOrFreemium -> yearData.sales.isZero
                    else -> downloads.none { it.firstOfMonth.year == year && it.downloads > 0 }
                }
            }
            .map { (year, yearData) ->
                val yearDateRange = YearMonthDayRange.ofYear(year)
                val trialResultAnyDuration = trialsTracker.getResult(yearDateRange)
                val trialResultTrialDuration = trialsTracker.getResultWithinConversionPeriod(yearDateRange, maxTrialDays)
                // The recurring revenue of the current year is incomplete,
                // it's the value of today, it changes until the end of the year
                val arrValue = yearData.annualRevenue.getResult().amounts.getTotalAmount()
                    .let { if (year == now.year) IncompleteValue(it) else it }
                // the sums of the current year and of the first year of the plugin, if it started within the year
                val isPartialYear = isPartialPeriod(yearDateRange.start, pluginStart)
                val incompleteSums = year == now.year || isPartialYear
                val churn = yearData.churn.getResult(null)
                SimpleDateTableRow(
                    values = mapOf(
                        columnYear to year,
                        columnSalesTotal to incompleteIf(yearData.sales.totalAmount, incompleteSums),
                        columnSalesFees to incompleteIf(yearData.sales.feesAmount, incompleteSums),
                        columnSalesPaid to incompleteIf(yearData.sales.paidAmount, incompleteSums),
                        columnARR to arrValue,
                        columnChurn to churn.getRenderedChurnRate(pluginId!!, isIncomplete = incompleteSums),
                        columnDownloads to incompleteIf(downloads
                            .filter { it.firstOfMonth.year == year }
                            .sumOf(MonthlyDownload::downloads)
                            .toBigInteger(), incompleteSums),
                        columnTrials to incompleteIf(trialResultAnyDuration.totalTrials.takeIf { it > 0 }?.toBigInteger() ?: NoValue, incompleteSums),
                        columnTrialsConverted to incompleteIf(trialResultTrialDuration.displayedConversionRate, incompleteSums),
                    ),
                    tooltips = mapOf(
                        columnYear to pluginStart?.let(::partialPeriodTooltip).takeIf { isPartialYear },
                        columnARR to IncompleteRecurringRevenueTooltip.takeIf { year == now.year },
                        columnChurn to churn.getChurnRateTooltip(isIncomplete = year == now.year),
                        columnTrialsConverted to conversionTooltip(trialResultAnyDuration, trialResultTrialDuration, maxTrialDays)
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
                            columnTrialsConverted to allTrialsTrialDuration.displayedConversionRate,
                        ),
                        tooltips = mapOf(
                            columnTrialsConverted to conversionTooltip(allTrialsAnyDuration, allTrialsTrialDuration, maxTrialDays),
                        )
                    )
                )
            )
        )
    }
}