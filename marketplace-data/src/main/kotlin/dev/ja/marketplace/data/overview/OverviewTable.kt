/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.overview

import dev.ja.marketplace.churn.ChurnProcessor
import dev.ja.marketplace.churn.CustomerChurnProcessor
import dev.ja.marketplace.churn.LicenseChurnProcessor
import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.CustomerInfo
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.MonthlyDownload
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.overview.OverviewTable.CustomerSegment.*
import dev.ja.marketplace.data.trackers.*
import java.util.*

class OverviewTable : SimpleDataTable("Overview", "overview", "table-striped tables-row") {
    private enum class CustomerSegment {
        AnnualFree,
        AnnualPaying,
        MonthlyFree,
        MonthlyPaying;

        companion object {
            fun of(licenseInfo: LicenseInfo): CustomerSegment {
                val isPaidLicense = licenseInfo.isPaidLicense
                return when {
                    isPaidLicense -> when (licenseInfo.sale.licensePeriod) {
                        LicensePeriod.Annual -> AnnualPaying
                        LicensePeriod.Monthly -> MonthlyPaying
                    }

                    else -> when (licenseInfo.sale.licensePeriod) {
                        LicensePeriod.Annual -> AnnualFree
                        LicensePeriod.Monthly -> MonthlyFree
                    }
                }
            }
        }
    }

    private data class MonthData(
        val year: Int,
        val month: Int,
        val customers: CustomerTracker<CustomerSegment>,
        val licenses: LicenseTracker<CustomerSegment>,
        val amounts: PaymentAmountTracker,
        val churnCustomersAnnual: ChurnProcessor<CustomerInfo>,
        val churnLicensesAnnual: ChurnProcessor<LicenseInfo>,
        val churnCustomersMonthly: ChurnProcessor<CustomerInfo>,
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
        val churnCustomersAnnual: ChurnProcessor<CustomerInfo>,
        val churnLicensesAnnual: ChurnProcessor<LicenseInfo>,
        val churnCustomersMonthly: ChurnProcessor<CustomerInfo>,
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
    private val years = TreeMap<Int, YearData>(Comparator.reverseOrder())

    private val columnYearMonth = DataTableColumn("month", null, "month")
    private val columnAmountTotal = DataTableColumn("sales", "Total Sales", "num")
    private val columnAmountFees = DataTableColumn("sales", "Fees", "num")
    private val columnAmountPaid = DataTableColumn("sales", "Invoice", "num")
    private val columnActiveLicenses = DataTableColumn(
        "customer-licenses", "Licenses", "num", tooltip = "Licenses at the end of month"
    )
    private val columnActiveLicensesPaying = DataTableColumn(
        "customer-licenses-paid", "Paid", "num", tooltip = "Paid licenses at the end of month"
    )
    private val columnMonthlyRecurringRevenue = DataTableColumn(
        "customer-mrr", "MRR", "num", tooltip = "Average monthly recurring revenue (MRR)"
    )
    private val columnAnnualRecurringRevenue = DataTableColumn(
        "customer-mrr", "ARR", "num", tooltip = "Average annual recurring revenue (MRR)"
    )

    private val columnLicenseChurnAnnual = DataTableColumn(
        "churn-annual-paid", "Churn (annual)", "num num-percentage", tooltip = "Churn of paid annual licenses"
    )

    private val columnLicenseChurnMonthly = DataTableColumn(
        "churn-monthly-paid", "Churn (monthly)", "num num-percentage", tooltip = "Churn of paid monthly licenses"
    )
    private val columnDownloads = DataTableColumn(
        "downloads", "↓", "num", tooltip = "Number of downloads in the month"
    )
    private val columnTrials = DataTableColumn(
        "trials", "Trials", "num", tooltip = "Number of new trials at the end of the month"
    )
    private val columnTrialsConverted = DataTableColumn(
        "trials-converted-length", "Conv.", "num", tooltip = "Percentage of converted trials of the month"
    )

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
        this.maxTrialDays = data.getPluginInfo().purchaseInfo?.trialPeriod ?: Marketplace.MAX_TRIAL_DAYS_DEFAULT

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
                    CustomerTracker(activeCustomerRange),
                    LicenseTracker(activeCustomerRange),
                    PaymentAmountTracker(currentMonth, exchangeRates),
                    createCustomerChurnProcessor(currentMonth),
                    createLicenseChurnProcessor(currentMonth),
                    createCustomerChurnProcessor(currentMonth),
                    createLicenseChurnProcessor(currentMonth),
                    createMonthlyRecurringRevenueTracker(currentMonth, data),
                    createAnnualRecurringRevenueTracker(currentMonth, data),
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
                createCustomerChurnProcessor(activeTimeRange),
                createLicenseChurnProcessor(activeTimeRange),
                createCustomerChurnProcessor(activeTimeRange),
                createLicenseChurnProcessor(activeTimeRange),
                months,
            )
        }

        trialTracker.init(data.getTrials() ?: emptyList())
    }

    private suspend fun createMonthlyRecurringRevenueTracker(month: YearMonthDayRange, pluginData: PluginData): RecurringRevenueTracker {
        return MonthlyRecurringRevenueTracker(
            month,
            pluginData.getContinuityDiscountTracker(),
            pluginData.pluginPricing!!,
            pluginData.exchangeRates
        )
    }

    private suspend fun createAnnualRecurringRevenueTracker(month: YearMonthDayRange, pluginData: PluginData): RecurringRevenueTracker {
        return AnnualRecurringRevenueTracker(
            month,
            pluginData.getContinuityDiscountTracker(),
            pluginData.pluginPricing!!,
            pluginData.exchangeRates
        )
    }

    override suspend fun process(sale: PluginSale) {
        trialTracker.processSale(sale)

        val yearData = years[sale.date.year]!!

        val monthData = yearData.months[sale.date.month]!!
        monthData.amounts.add(sale.date, sale.amountUSD, sale.amount)
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        val customer = licenseInfo.sale.customer
        val licensePeriod = licenseInfo.sale.licensePeriod
        val isPaidLicense = licenseInfo.isPaidLicense
        val isRenewal = licenseInfo.isRenewalLicense
        val customerSegment = CustomerSegment.of(licenseInfo)

        years.values.forEach { year ->
            year.churnCustomersAnnual.processValue(
                customer,
                licenseInfo.validity,
                licensePeriod == LicensePeriod.Annual && isPaidLicense,
                isRenewal
            )
            year.churnLicensesAnnual.processValue(
                licenseInfo,
                licenseInfo.validity,
                licensePeriod == LicensePeriod.Annual && isPaidLicense,
                isRenewal
            )

            year.churnCustomersMonthly.processValue(
                customer,
                licenseInfo.validity,
                licensePeriod == LicensePeriod.Monthly && isPaidLicense,
                isRenewal
            )
            year.churnLicensesMonthly.processValue(
                licenseInfo,
                licenseInfo.validity,
                licensePeriod == LicensePeriod.Monthly && isPaidLicense,
                isRenewal
            )

            year.months.values.forEach { month ->
                month.customers.add(customerSegment, licenseInfo)
                month.licenses.add(customerSegment, licenseInfo)

                month.mrrTracker.processLicenseSale(licenseInfo)
                month.arrTracker.processLicenseSale(licenseInfo)

                month.churnCustomersAnnual.processValue(
                    customer,
                    licenseInfo.validity,
                    licensePeriod == LicensePeriod.Annual && isPaidLicense,
                    isRenewal
                )
                month.churnLicensesAnnual.processValue(
                    licenseInfo,
                    licenseInfo.validity,
                    licensePeriod == LicensePeriod.Annual && isPaidLicense,
                    isRenewal
                )

                month.churnCustomersMonthly.processValue(
                    customer,
                    licenseInfo.validity,
                    licensePeriod == LicensePeriod.Monthly && isPaidLicense,
                    isRenewal
                )
                month.churnLicensesMonthly.processValue(
                    licenseInfo,
                    licenseInfo.validity,
                    licensePeriod == LicensePeriod.Monthly && isPaidLicense,
                    isRenewal
                )
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

                    val mrrResult = when {
                        isCurrentMonth -> null
                        else -> monthData.mrrTracker.getResult()
                    }

                    val arrResult = when {
                        isCurrentMonth -> null
                        else -> monthData.arrTracker.getResult()
                    }

                    val mrrValue = mrrResult?.amounts?.getTotalAmount()
                    val arrValue = arrResult?.amounts?.getTotalAmount()

                    val annualLicenseChurn = when {
                        isCurrentMonth -> null
                        else -> monthData.churnLicensesAnnual.getResult(LicensePeriod.Annual)
                    }
                    val annualLicenseChurnRate = annualLicenseChurn?.getRenderedChurnRate(pluginId)

                    val monthlyLicenseChurn = when {
                        isCurrentMonth -> null
                        else -> monthData.churnLicensesMonthly.getResult(LicensePeriod.Monthly)
                    }
                    val monthlyLicenseChurnRate = monthlyLicenseChurn?.getRenderedChurnRate(pluginId)

                    val cssClass = when {
                        isCurrentMonth -> "today"
                        monthData.isEmpty -> "disabled"
                        else -> null
                    }

                    val annualLicensesFree = monthData.licenses.segmentCustomerCount(AnnualFree)
                    val annualLicensesPaying = monthData.licenses.segmentCustomerCount(AnnualPaying)
                    val monthlyLicensesPaying = monthData.licenses.segmentCustomerCount(MonthlyPaying)
                    val totalLicenses = monthData.licenses.totalLicenseCount
                    val totalLicensesPaying = monthData.licenses.paidLicensesCount

                    val trialsMonth = trialTracker.getResultBySaleDate(monthDateRange, monthDateRange)
                    val trialsMonthAnyLength = trialTracker.getResult(monthDateRange)
                    val trialsMonthByLength = trialTracker.getResultByTrialDuration(monthDateRange, maxTrialDays)
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
                            columnMonthlyRecurringRevenue to (mrrValue ?: NoValue),
                            columnAnnualRecurringRevenue to (arrValue ?: NoValue),
                            columnAmountTotal to monthData.amounts.totalAmount,
                            columnAmountFees to monthData.amounts.feesAmount,
                            columnAmountPaid to monthData.amounts.paidAmount,
                            columnLicenseChurnAnnual to (annualLicenseChurnRate ?: NoValue),
                            columnLicenseChurnMonthly to (monthlyLicenseChurnRate ?: NoValue),
                            columnDownloads to (downloadCount.takeIf { it > 0 }?.toBigInteger() ?: NoValue),
                            columnTrials to (trialsMonth.totalTrials.takeIf { it > 0 }?.toBigInteger() ?: NoValue),
                            columnTrialsConverted to trialsMonthByLength.convertedTrialsPercentage,
                        ),
                        tooltips = mapOf(
                            columnActiveLicenses to "$annualLicensesPaying annual (paying)" +
                                    "\n$annualLicensesFree annual (free)" +
                                    "\n$monthlyLicensesPaying monthly (paying)",
                            columnActiveLicensesPaying to paidLicensesTooltip,
                            columnLicenseChurnAnnual to annualLicenseChurn?.churnRateTooltip,
                            columnLicenseChurnMonthly to monthlyLicenseChurn?.churnRateTooltip,
                            columnTrialsConverted to trialsMonthAnyLength.getTooltipConverted() +
                                    "\n" +
                                    trialsMonthByLength.getTooltipConverted(maxTrialDays),
                        ),
                        cssClass = cssClass
                    )
                }

                val yearLicenseChurnAnnual = yearData.churnLicensesAnnual.getResult(LicensePeriod.Annual)
                val yearLicenseChurnMonthly = yearData.churnLicensesMonthly.getResult(LicensePeriod.Monthly)

                val trialsYear = trialTracker.getResultBySaleDate(yearDateRange, yearDateRange)
                val trialsYearTrialLength = trialTracker.getResultByTrialDuration(yearDateRange, maxTrialDays)
                val trialsYearAnyDuration = trialTracker.getResult(yearDateRange)

                SimpleTableSection(
                    rows,
                    "$year",
                    footer = SimpleRowGroup(
                        SimpleDateTableRow(
                            values = mapOf(
                                columnLicenseChurnAnnual to yearLicenseChurnAnnual.getRenderedChurnRate(pluginId),
                                columnLicenseChurnMonthly to yearLicenseChurnMonthly.getRenderedChurnRate(pluginId),
                                columnDownloads to (yearData.months.values.sumOf { it.downloads }.takeIf { it > 0 }?.toBigInteger()
                                    ?: NoValue),
                                columnTrials to (trialsYear.totalTrials.takeIf { it > 0 }?.toBigInteger() ?: NoValue),
                                columnTrialsConverted to trialsYearAnyDuration.convertedTrialsPercentage,
                            ),
                            tooltips = mapOf(
                                columnLicenseChurnAnnual to yearLicenseChurnAnnual.churnRateTooltip,
                                columnLicenseChurnMonthly to yearLicenseChurnMonthly.churnRateTooltip,
                                columnTrialsConverted to
                                        trialsYearAnyDuration.getTooltipConverted()
                                        + "\n"
                                        + trialsYearTrialLength.getTooltipConverted(maxTrialDays),
                            )
                        )
                    )
                )
            }
    }

    private fun createCustomerChurnProcessor(timeRange: YearMonthDayRange): ChurnProcessor<CustomerInfo> {
        val churnDate = timeRange.end
        val activeDate = timeRange.start.add(0, 0, -1)
        val processor = CustomerChurnProcessor(activeDate, churnDate)
        processor.init()
        return processor
    }

    private fun createLicenseChurnProcessor(timeRange: YearMonthDayRange): ChurnProcessor<LicenseInfo> {
        val churnDate = timeRange.end
        val activeDate = timeRange.start.add(0, 0, -1)
        val processor = LicenseChurnProcessor(activeDate, churnDate)
        processor.init()
        return processor
    }
}
