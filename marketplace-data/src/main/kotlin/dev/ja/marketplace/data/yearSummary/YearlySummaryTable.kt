/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.AnnualRecurringRevenueTracker
import dev.ja.marketplace.data.trackers.PaymentAmountTracker
import dev.ja.marketplace.data.trackers.SimpleTrialTracker
import dev.ja.marketplace.data.trackers.TrialTracker
import java.util.*

class YearlySummaryTable : SimpleDataTable("Years", "years", "table-column-wide") {
    private lateinit var downloads: List<MonthlyDownload>

    private val allTrialsTracker: TrialTracker = SimpleTrialTracker()
    private val data = TreeMap<Int, YearSummary>(Comparator.reverseOrder())

    private data class YearSummary(
        val sales: PaymentAmountTracker,
        val trials: TrialTracker,
        val annualRevenue: AnnualRecurringRevenueTracker,
    )

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.downloads = data.downloadsMonthly

        val now = YearMonthDay.now()
        for (year in Marketplace.Birthday.year..now.year) {
            val yearRange = YearMonthDayRange.ofYear(year)
            this.data[year] = YearSummary(
                PaymentAmountTracker(yearRange, exchangeRates),
                SimpleTrialTracker { it.date in yearRange },
                AnnualRecurringRevenueTracker(yearRange, data.continuityDiscountTracker!!, data.pluginPricing!!, data.exchangeRates)
            )
        }

        if (data.trials != null) {
            for (trial in data.trials) {
                allTrialsTracker.registerTrial(trial)
                this.data[trial.date.year]?.trials?.registerTrial(trial)
            }
        }
    }

    override suspend fun process(sale: PluginSale) {
        allTrialsTracker.processSale(sale)

        val yearData = data[sale.date.year]!!
        yearData.sales.add(sale.date, sale.amountUSD, sale.amount, sale.currency)
        yearData.trials.processSale(sale)
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
        val allTrialsResult = allTrialsTracker.getResult()

        val rows = data.entries.toList()
            .dropLastWhile { it.value.sales.isZero }
            .map { (year, yearData) ->
                val lastOfYear = YearMonthDay(year, 12, 31)
                val trialResult = yearData.trials.getResult()
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
                        columnARR to (arrResult?.amounts?.getConvertedResult(lastOfYear) ?: NoValue),
                        columnDownloads to downloads
                            .filter { it.firstOfMonth.year == year }
                            .sumOf(MonthlyDownload::downloads)
                            .toBigInteger(),
                        columnTrials to trialResult.totalTrials.toBigInteger(),
                        columnTrialsConverted to trialResult.convertedTrialsPercentage,
                    ),
                    tooltips = mapOf(
                        columnTrialsConverted to trialResult.tooltipConverted,
                        columnARR to arrResult?.renderTooltip(),
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
                        columnSalesTotal to rows.sumOf { (it.values[columnSalesTotal] as AmountWithCurrency).amount }
                            .withCurrency(exchangeRates.targetCurrencyCode),
                        columnSalesFees to rows.sumOf { (it.values[columnSalesFees] as AmountWithCurrency).amount }
                            .withCurrency(exchangeRates.targetCurrencyCode),
                        columnSalesPaid to rows.sumOf { (it.values[columnSalesPaid] as AmountWithCurrency).amount }
                            .withCurrency(exchangeRates.targetCurrencyCode),
                        columnDownloads to downloads.sumOf { it.downloads.toBigInteger() },
                        columnTrials to allTrialsResult.totalTrials.toBigInteger(),
                        columnTrialsConverted to allTrialsResult.convertedTrialsPercentage,
                    )
                )
            )
        )
    }
}