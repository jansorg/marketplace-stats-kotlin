/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.overview

import dev.ja.marketplace.churn.ChurnProcessor
import dev.ja.marketplace.churn.ChurnValidities
import dev.ja.marketplace.churn.LicenseChurnProcessor
import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.MonthlyDownload
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.overview.OverviewTable.CustomerSegment.*
import dev.ja.marketplace.data.trackers.*
import java.util.*

class OverviewTable : SimpleDataTable("Overview", "overview", "table-striped tables-row") {
    private enum class CustomerSegment {
        PerpetualFree,
        PerpetualPaying,
        AnnualFree,
        AnnualPaying,
        MonthlyFree,
        MonthlyPaying;

        companion object {
            fun of(licenseInfo: LicenseInfo): CustomerSegment {
                val isPaidLicense = licenseInfo.isPaidLicense
                return when {
                    isPaidLicense -> when (licenseInfo.sale.licensePeriod) {
                        LicensePeriod.Perpetual -> PerpetualPaying
                        LicensePeriod.Monthly -> MonthlyPaying
                        LicensePeriod.Annual -> AnnualPaying
                    }

                    else -> when (licenseInfo.sale.licensePeriod) {
                        LicensePeriod.Perpetual -> PerpetualFree
                        LicensePeriod.Annual -> AnnualFree
                        LicensePeriod.Monthly -> MonthlyFree
                    }
                }
            }
        }
    }

    private class MonthData(
        val year: Int,
        val month: Int,
        val licenses: LicenseTracker<CustomerSegment>,
        val amounts: PaymentAmountTracker,
        val churnLicensesAnnual: ChurnProcessor<LicenseInfo>,
        val churnLicensesMonthly: ChurnProcessor<LicenseInfo>,
        val mrrTracker: RecurringRevenueTracker,
        val arrTracker: RecurringRevenueTracker,
        val downloads: Long,
    ) {
        val isEmpty: Boolean
            get() {
                return amounts.isZero
            }
    }

    private data class YearData(
        val year: Int,
        val churnLicensesAnnual: ChurnProcessor<LicenseInfo>,
        val churnLicensesMonthly: ChurnProcessor<LicenseInfo>,
        val months: Map<Int, MonthData>,
    ) {
        val isEmpty: Boolean
            get() {
                return months.isEmpty() || months.all { it.value.isEmpty }
            }
    }

    private var pluginId: PluginId? = null
    private var maxTrialDays: Int = Marketplace.MAX_TRIAL_DAYS_DEFAULT

    private lateinit var downloadsMonthly: List<MonthlyDownload>
    private var downloadsTotal: Long = 0

    private val trialTracker: TrialTracker = SimpleTrialTracker()
    private lateinit var churnValidities: ChurnValidities
    private val years = TreeMap<Int, YearData>(Comparator.reverseOrder())

    private val columnYearMonth = DataTableColumn("month", null, "month")
    private val columnAmountTotal = DataTableColumn("sales", "Total Sales", "num", dailyChartSeries = OverviewDailyColumn.Sales.id, chartMeasure = ChartMeasure.Flow)
    private val columnAmountFees = DataTableColumn("sales", "Fees", "num")
    private val columnAmountPaid = DataTableColumn("sales", "Invoice", "num", dailyChartSeries = OverviewDailyColumn.Invoice.id, chartMeasure = ChartMeasure.Flow)
    private val columnActiveLicenses = DataTableColumn(
        "customer-licenses", "Licenses", "num", tooltip = "Licenses at the end of month",
        dailyChartSeries = OverviewDailyColumn.Licenses.id,
        chartMeasure = ChartMeasure.Stock,
    )
    private val columnActiveLicensesPaying = DataTableColumn(
        "customer-licenses-paid", "Paid", "num", tooltip = "Paid licenses at the end of month", chartTitle = "Paid licenses",
        dailyChartSeries = OverviewDailyColumn.PaidLicenses.id,
        chartMeasure = ChartMeasure.Stock,
    )
    private val columnMonthlyRecurringRevenue = DataTableColumn(
        "customer-mrr", "MRR", "num", tooltip = "Average monthly recurring revenue (MRR)",
        dailyChartSeries = OverviewDailyColumn.Mrr.id,
        chartMeasure = ChartMeasure.Stock,
    )
    private val columnAnnualRecurringRevenue = DataTableColumn(
        "customer-arr", "ARR", "num", tooltip = "Average annual recurring revenue (ARR)",
        dailyChartSeries = OverviewDailyColumn.Arr.id,
        chartMeasure = ChartMeasure.Stock,
    )

    private val columnLicenseChurnAnnual = DataTableColumn(
        "churn-annual-paid", "Churn (annual)", "num num-percentage", tooltip = "Churn of paid annual licenses",
        chartMeasure = ChartMeasure.Ratio,
        dailyChartSeries = OverviewDailyColumn.ChurnAnnual.id,
        yearlyChartSeries = OverviewDailyColumn.ChurnAnnualOfYear.id,
    )

    private val columnLicenseChurnMonthly = DataTableColumn(
        "churn-monthly-paid", "Churn (monthly)", "num num-percentage", tooltip = "Churn of paid monthly licenses",
        chartMeasure = ChartMeasure.Ratio,
        dailyChartSeries = OverviewDailyColumn.ChurnMonthly.id,
        yearlyChartSeries = OverviewDailyColumn.ChurnMonthlyOfYear.id,
    )
    private val columnDownloads = DataTableColumn(
        "downloads", "↓", "num", chartTitle = "Downloads", tooltip = "Number of downloads in the month",
        dailyChartSeries = OverviewDailyColumn.Downloads.id,
        chartMeasure = ChartMeasure.Flow,
    )
    private val columnTrials = DataTableColumn(
        "trials", "Trials", "num", tooltip = "Number of new trials at the end of the month",
        dailyChartSeries = OverviewDailyColumn.Trials.id,
        chartMeasure = ChartMeasure.Flow,
    )
    private val columnTrialsConverted = DataTableColumn(
        "trials-converted-length", "Conv.", "num",
        tooltip = "Percentage of trials started in the month, which were converted by a paid new license " +
                "within the trial period and ${Marketplace.TRIAL_CONVERSION_EXTRA_DAYS} days after it",
        chartMeasure = ChartMeasure.Ratio,
    )

    override val isChartable: Boolean = true
    override val chartPeriod: ChartPeriod = ChartPeriod.Month

    override val columns: List<DataTableColumn> by lazy {
        listOfNotNull(
            columnYearMonth,
            columnAmountTotal,
            columnAmountPaid,
            columnActiveLicenses,
            columnActiveLicensesPaying,
            columnMonthlyRecurringRevenue,
            columnAnnualRecurringRevenue,
            columnLicenseChurnAnnual,
            columnLicenseChurnMonthly,
            columnDownloads,
            columnTrials.takeIf { maxTrialDays > 0 },
            columnTrialsConverted.takeIf { maxTrialDays > 0 },
        )
    }

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.pluginId = data.pluginId
        this.maxTrialDays = data.getPluginInfo().maxTrialDays

        val now = YearMonthDay.now()
        this.downloadsMonthly = data.getDownloadsMonthly()
        this.downloadsTotal = data.getTotalDownloads()

        for (year in Marketplace.Birthday.year..now.year) {
            val monthRange = when (year) {
                now.year -> 1..now.month
                else -> 1..12
            }

            val months: Map<Int, MonthData> = monthRange.associateWithTo(TreeMap(Comparator.reverseOrder())) { month ->
                val currentMonth = YearMonthDayRange.ofMonth(year, month)
                val activeCustomerRange = when {
                    now in currentMonth -> currentMonth.copy(end = now)
                    else -> currentMonth
                }

                MonthData(
                    year,
                    month,
                    LicenseTracker(activeCustomerRange),
                    PaymentAmountTracker(currentMonth, exchangeRates),
                    // churn of the current month until today, licenses expiring later in the month may still be renewed
                    createLicenseChurnProcessor(activeCustomerRange),
                    createLicenseChurnProcessor(activeCustomerRange),
                    // the recurring revenue of the current month at today, like the licenses
                    createMonthlyRecurringRevenueTracker(activeCustomerRange, data),
                    createAnnualRecurringRevenueTracker(activeCustomerRange, data),
                    downloadsMonthly
                        .firstOrNull { it.firstOfMonth.year == year && it.firstOfMonth.month == month }
                        ?.downloads
                        ?: 0L,
                )
            }

            // annual churn
            val activeTimeRange = YearMonthDayRange(YearMonthDay(year, 1, 1), minOf(now, YearMonthDay(year, 12, 31)))
            years[year] = YearData(
                year,
                createLicenseChurnProcessor(activeTimeRange),
                createLicenseChurnProcessor(activeTimeRange),
                months,
            )
        }

        trialTracker.init(data.getTrials() ?: emptyList())
        churnValidities = ChurnValidities(data.getLicenses() ?: emptyList())
    }

    private fun createMonthlyRecurringRevenueTracker(month: YearMonthDayRange, pluginData: PluginData): RecurringRevenueTracker {
        return MonthlyRecurringRevenueTracker(month, pluginData.exchangeRates)
    }

    private fun createAnnualRecurringRevenueTracker(month: YearMonthDayRange, pluginData: PluginData): RecurringRevenueTracker {
        return AnnualRecurringRevenueTracker(month, pluginData.exchangeRates)
    }

    override suspend fun process(sale: PluginSale) {
        trialTracker.processSale(sale)

        val yearData = years[sale.date.year]!!

        val monthData = yearData.months[sale.date.month]!!
        monthData.amounts.add(sale.date, sale.amountUSD, sale.amount)
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        val licensePeriod = licenseInfo.sale.licensePeriod
        val isPaidLicense = licenseInfo.isPaidLicense
        val customerSegment = CustomerSegment.of(licenseInfo)
        val validity = churnValidities.validity(licenseInfo)

        years.values.forEach { year ->
            if (validity != null) {
                year.churnLicensesAnnual.processValue(
                    licenseInfo,
                    validity,
                    licensePeriod == LicensePeriod.Annual && isPaidLicense,
                )

                year.churnLicensesMonthly.processValue(
                    licenseInfo,
                    validity,
                    licensePeriod == LicensePeriod.Monthly && isPaidLicense,
                )
            }

            year.months.values.forEach { month ->
                month.licenses.add(customerSegment, licenseInfo)

                month.mrrTracker.processLicenseSale(licenseInfo)
                month.arrTracker.processLicenseSale(licenseInfo)

                if (validity != null) {
                    month.churnLicensesAnnual.processValue(
                        licenseInfo,
                        validity,
                        licensePeriod == LicensePeriod.Annual && isPaidLicense,
                    )

                    month.churnLicensesMonthly.processValue(
                        licenseInfo,
                        validity,
                        licensePeriod == LicensePeriod.Monthly && isPaidLicense,
                    )
                }
            }
        }
    }

    override suspend fun createSections(): List<DataTableSection> {
        val now = YearMonthDay.now()
        val pluginId = pluginId!!
        return years.entries
            .toMutableList()
            .dropLastWhile { it.value.isEmpty } // don't show empty years
            .map { (year, yearData) ->
                val yearDateRange = YearMonthDayRange.ofYear(year)
                val rows = yearData.months.entries.map { (month, monthData) ->
                    val isCurrentMonth = now.year == year && now.month == month
                    val monthDateRange = YearMonthDayRange.ofMonth(year, month)

                    // The recurring revenue of the current month is incomplete,
                    // it's the value of today, it changes until the end of the month
                    val mrrValue = monthData.mrrTracker.getResult().amounts.getTotalAmount()
                        .let { if (isCurrentMonth) IncompleteValue(it) else it }
                    val arrValue = monthData.arrTracker.getResult().amounts.getTotalAmount()
                        .let { if (isCurrentMonth) IncompleteValue(it) else it }

                    val annualLicenseChurn = monthData.churnLicensesAnnual.getResult(LicensePeriod.Annual)
                    val annualLicenseChurnRate = annualLicenseChurn.getRenderedChurnRate(pluginId, isIncomplete = isCurrentMonth)

                    val monthlyLicenseChurn = monthData.churnLicensesMonthly.getResult(LicensePeriod.Monthly)
                    val monthlyLicenseChurnRate = monthlyLicenseChurn.getRenderedChurnRate(pluginId, isIncomplete = isCurrentMonth)

                    val cssClass = when {
                        isCurrentMonth -> "today"
                        monthData.isEmpty -> "disabled"
                        else -> null
                    }

                    val annualLicensesFree = monthData.licenses.segmentLicenseCount(AnnualFree)
                    val annualLicensesPaying = monthData.licenses.segmentLicenseCount(AnnualPaying)
                    val monthlyLicensesPaying = monthData.licenses.segmentLicenseCount(MonthlyPaying)
                    val totalLicenses = monthData.licenses.totalLicenseCount
                    val totalLicensesPaying = monthData.licenses.paidLicensesCount

                    val trialsMonthAnyLength = trialTracker.getResult(monthDateRange)
                    val trialsMonthByLength = trialTracker.getResultWithinConversionPeriod(monthDateRange, maxTrialDays)
                    val downloadCount = monthData.downloads

                    val paidLicensesTooltip =
                        """
                        $annualLicensesPaying annual
                        $monthlyLicensesPaying monthly
                        """.trimIndent()

                    SimpleDateTableRow(
                        values = mapOf(
                            columnYearMonth to String.format("%02d-%02d", year, month),
                            columnActiveLicenses to totalLicenses.toBigInteger(),
                            columnActiveLicensesPaying to totalLicensesPaying.toBigInteger(),
                            columnMonthlyRecurringRevenue to mrrValue,
                            columnAnnualRecurringRevenue to arrValue,
                            columnAmountTotal to monthData.amounts.totalAmount,
                            columnAmountFees to monthData.amounts.feesAmount,
                            columnAmountPaid to monthData.amounts.paidAmount,
                            columnLicenseChurnAnnual to annualLicenseChurnRate,
                            columnLicenseChurnMonthly to monthlyLicenseChurnRate,
                            columnDownloads to (downloadCount.takeIf { it > 0 }?.toBigInteger() ?: NoValue),
                            columnTrials to (trialsMonthAnyLength.totalTrials.takeIf { it > 0 }?.toBigInteger() ?: NoValue),
                            columnTrialsConverted to trialsMonthByLength.displayedConversionRate,
                        ),
                        tooltips = mapOf(
                            columnActiveLicenses to "$annualLicensesPaying annual (paying)" +
                                    "\n$annualLicensesFree annual (free)" +
                                    "\n$monthlyLicensesPaying monthly (paying)",
                            columnActiveLicensesPaying to paidLicensesTooltip,
                            columnMonthlyRecurringRevenue to IncompleteRecurringRevenueTooltip.takeIf { isCurrentMonth },
                            columnAnnualRecurringRevenue to IncompleteRecurringRevenueTooltip.takeIf { isCurrentMonth },
                            columnLicenseChurnAnnual to annualLicenseChurn.getChurnRateTooltip(isIncomplete = isCurrentMonth),
                            columnLicenseChurnMonthly to monthlyLicenseChurn.getChurnRateTooltip(isIncomplete = isCurrentMonth),
                            columnTrialsConverted to conversionTooltip(trialsMonthAnyLength, trialsMonthByLength, maxTrialDays),
                        ),
                        cssClass = cssClass
                    )
                }

                val isCurrentYear = year == now.year
                val yearLicenseChurnAnnual = yearData.churnLicensesAnnual.getResult(LicensePeriod.Annual)
                val yearLicenseChurnMonthly = yearData.churnLicensesMonthly.getResult(LicensePeriod.Monthly)

                val trialsYearTrialLength = trialTracker.getResultWithinConversionPeriod(yearDateRange, maxTrialDays)
                val trialsYearAnyDuration = trialTracker.getResult(yearDateRange)

                SimpleTableSection(
                    rows,
                    "$year",
                    footer = SimpleRowGroup(
                        SimpleDateTableRow(
                            values = mapOf(
                                columnLicenseChurnAnnual to yearLicenseChurnAnnual.getRenderedChurnRate(pluginId, isIncomplete = isCurrentYear),
                                columnLicenseChurnMonthly to yearLicenseChurnMonthly.getRenderedChurnRate(pluginId, isIncomplete = isCurrentYear),
                                columnDownloads to (yearData.months.values.sumOf { it.downloads }.takeIf { it > 0 }?.toBigInteger()
                                    ?: NoValue),
                                columnTrials to (trialsYearAnyDuration.totalTrials.takeIf { it > 0 }?.toBigInteger() ?: NoValue),
                                columnTrialsConverted to trialsYearTrialLength.displayedConversionRate,
                            ),
                            tooltips = mapOf(
                                columnLicenseChurnAnnual to yearLicenseChurnAnnual.getChurnRateTooltip(isIncomplete = isCurrentYear),
                                columnLicenseChurnMonthly to yearLicenseChurnMonthly.getChurnRateTooltip(isIncomplete = isCurrentYear),
                                columnTrialsConverted to
                                        conversionTooltip(trialsYearAnyDuration, trialsYearTrialLength, maxTrialDays),
                            )
                        )
                    )
                )
            }
    }

    private fun createLicenseChurnProcessor(timeRange: YearMonthDayRange): ChurnProcessor<LicenseInfo> {
        val churnDate = timeRange.end
        val activeDate = timeRange.start.add(0, 0, -1)
        val processor = LicenseChurnProcessor(activeDate, churnDate)
        return processor
    }
}
