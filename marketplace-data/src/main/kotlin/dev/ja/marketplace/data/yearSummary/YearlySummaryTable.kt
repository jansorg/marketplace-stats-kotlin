/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.Currency
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.AnnualRecurringRevenueTracker
import dev.ja.marketplace.data.trackers.SimpleTrialTracker
import dev.ja.marketplace.data.trackers.TrialTracker
import dev.ja.marketplace.util.isZero
import java.util.*

class YearlySummaryTable : SimpleDataTable("Years", "years", "table-column-wide"), MarketplaceDataSink {
    private lateinit var downloads: List<MonthlyDownload>
    private val allTrialsTracker: TrialTracker = SimpleTrialTracker()
    private val data = TreeMap<Int, YearSummary>(Comparator.reverseOrder())

    private data class YearSummary(
        val sales: PaymentAmountTracker,
        val trials: TrialTracker,
        val annualRevenue: AnnualRecurringRevenueTracker,
    )

    override suspend fun init(data: PluginData) {
        this.downloads = data.downloadsMonthly

        val now = YearMonthDay.now()
        for (year in Marketplace.Birthday.year..now.year) {
            val yearRange = YearMonthDayRange.ofYear(year)
            this.data[year] = YearSummary(
                PaymentAmountTracker(yearRange),
                SimpleTrialTracker { it.date in yearRange },
                AnnualRecurringRevenueTracker(yearRange, data.marketplacePluginInfo!!, data.continuityDiscountTracker!!)
            )
        }

        if (data.trials != null) {
            for (trial in data.trials) {
                allTrialsTracker.registerTrial(trial)
                this.data[trial.date.year]?.trials?.registerTrial(trial)
            }
        }
    }

    override fun process(sale: PluginSale) {
        allTrialsTracker.processSale(sale)

        val yearData = data[sale.date.year]!!
        yearData.sales.add(sale.date, sale.amountUSD)
        yearData.trials.processSale(sale)
    }

    override fun process(licenseInfo: LicenseInfo) {
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

    override fun createSections(): List<DataTableSection> {
        val now = YearMonthDay.now()
        val allTrialsResult = allTrialsTracker.getResult()

        val rows = data.entries.toList()
            .dropLastWhile { it.value.sales.totalAmountUSD.isZero() }
            .map { (year, yearData) ->
                val trialResult = yearData.trials.getResult()
                SimpleDateTableRow(
                    values = mapOf(
                        columnYear to year,
                        columnSalesTotal to yearData.sales.totalAmountUSD.withCurrency(Currency.USD),
                        columnSalesFees to yearData.sales.feesAmountUSD.withCurrency(Currency.USD),
                        columnSalesPaid to yearData.sales.paidAmountUSD.withCurrency(Currency.USD),
                        columnARR to (when {
                            year != now.year -> yearData.annualRevenue.getResult().averageAmount.withCurrency(Currency.USD)
                            else -> NoValue
                        }),
                        columnDownloads to downloads
                            .filter { it.firstOfMonth.year == year }
                            .sumOf(MonthlyDownload::downloads)
                            .toBigInteger(),
                        columnTrials to trialResult.totalTrials.toBigInteger(),
                        columnTrialsConverted to trialResult.convertedTrialsPercentage,
                    ),
                    tooltips = mapOf(
                        columnTrialsConverted to trialResult.tooltipConverted,
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
                        columnSalesTotal to data.values.sumOf { it.sales.totalAmountUSD }.withCurrency(Currency.USD),
                        columnSalesFees to data.values.sumOf { it.sales.feesAmountUSD }.withCurrency(Currency.USD),
                        columnSalesPaid to data.values.sumOf { it.sales.paidAmountUSD }.withCurrency(Currency.USD),
                        columnDownloads to downloads.sumOf { it.downloads.toBigInteger() },
                        columnTrials to allTrialsResult.totalTrials.toBigInteger(),
                        columnTrialsConverted to allTrialsResult.convertedTrialsPercentage,
                    )
                )
            )
        )
    }
}