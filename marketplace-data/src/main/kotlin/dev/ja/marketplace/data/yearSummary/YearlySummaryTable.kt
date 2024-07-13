/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.*
import java.util.*

class YearlySummaryTable : SimpleDataTable("Years", "years", "table-column-wide") {
    private var maxTrialDays: Int = Marketplace.MAX_TRIAL_DAYS_DEFAULT
    private lateinit var downloads: List<MonthlyDownload>

    private val trialsTracker: TrialTracker = SimpleTrialTracker()
    private val data = TreeMap<Int, YearSummary>(Comparator.reverseOrder())
    private lateinit var totalSales: PaymentAmountTracker

    private data class YearSummary(
        val sales: PaymentAmountTracker,
        val annualRevenue: AnnualRecurringRevenueTracker,
    )

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.maxTrialDays = data.pluginInfo.purchaseInfo?.trialPeriod ?: Marketplace.MAX_TRIAL_DAYS_DEFAULT
        this.downloads = data.downloadsMonthly
        this.totalSales = PaymentAmountTracker(YearMonthDayRange.MAX, data.exchangeRates)

        val now = YearMonthDay.now()
        for (year in Marketplace.Birthday.year..now.year) {
            val yearRange = YearMonthDayRange.ofYear(year)
            this.data[year] = YearSummary(
                PaymentAmountTracker(yearRange, data.exchangeRates),
                AnnualRecurringRevenueTracker(yearRange, data.continuityDiscountTracker!!, data.pluginPricing!!, data.exchangeRates)
            )
        }

        trialsTracker.init(data.trials ?: emptyList())
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

    private val columnYear = DataTableColumn("year", null)
    private val columnSalesTotal = DataTableColumn("sales", "Sales Total", "num")
    private val columnSalesFees = DataTableColumn("fees", "Fees", "num")
    private val columnSalesPaid = DataTableColumn("paid", "Paid", "num")
    private val columnARR = DataTableColumn("arr", "ARR", "num")
    private val columnDownloads = DataTableColumn("downloads", "↓", "num", tooltip = "Downloads")
    private val columnTrials = DataTableColumn("trials", "Trials", "num")
    private val columnTrialsConverted = DataTableColumn("trials-converted", "Conv. Trials", "num")

    override val columns: List<DataTableColumn> = listOf(
        columnYear,
        columnSalesTotal,
        columnSalesFees,
        columnSalesPaid,
        columnARR,
        columnDownloads,
        columnTrials,
        columnTrialsConverted,
    )

    override suspend fun createSections(): List<DataTableSection> {
        val now = YearMonthDay.now()
        val allTrialsAnyDuration = trialsTracker.getResult(YearMonthDayRange.MAX)
        val allTrialsTrialDuration = trialsTracker.getResultByTrialDuration(YearMonthDayRange.MAX, maxTrialDays)

        val rows = data.entries.toList()
            .dropLastWhile { it.value.sales.isZero }
            .map { (year, yearData) ->
                val yearDateRange = YearMonthDayRange.ofYear(year)
                val trialResultAnyDuration = trialsTracker.getResult(yearDateRange)
                val trialResultTrialDuration = trialsTracker.getResultByTrialDuration(yearDateRange, maxTrialDays)
                val arrResult = when {
                    year == now.year -> null
                    else -> yearData.annualRevenue.getResult()
                }
                SimpleDateTableRow(
                    values = mapOf(
                        columnYear to year,
                        columnSalesTotal to yearData.sales.totalAmount,
                        columnSalesFees to yearData.sales.feesAmount,
                        columnSalesPaid to yearData.sales.paidAmount,
                        columnARR to (arrResult?.amounts?.getTotalAmount() ?: NoValue),
                        columnDownloads to downloads
                            .filter { it.firstOfMonth.year == year }
                            .sumOf(MonthlyDownload::downloads)
                            .toBigInteger(),
                        columnTrials to trialResultAnyDuration.totalTrials.toBigInteger(),
                        columnTrialsConverted to trialResultAnyDuration.convertedTrialsPercentage,
                    ),
                    tooltips = mapOf(
                        columnTrialsConverted to trialResultAnyDuration.getTooltipConverted() +
                                "\n" +
                                trialResultTrialDuration.getTooltipConverted(maxTrialDays)
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
                            columnTrialsConverted to allTrialsAnyDuration.convertedTrialsPercentage,
                        ),
                        tooltips = mapOf(
                            columnTrialsConverted to allTrialsAnyDuration.getTooltipConverted() +
                                    "\n" +
                                    allTrialsTrialDuration.getTooltipConverted(maxTrialDays),
                        )
                    )
                )
            )
        )
    }
}