/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.overview

import dev.ja.marketplace.churn.ChurnProcessor
import dev.ja.marketplace.churn.MarketplaceChurnProcessor
import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.Currency
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.overview.OverviewTable.CustomerSegment.*
import java.math.BigDecimal
import java.util.*

class OverviewTable :
    SimpleDataTable("Overview", "overview", "table-striped tables-row"),
    MarketplaceDataSink {

    private enum class CustomerSegment {
        AnnualFree,
        AnnualPaying,
        MonthlyFree,
        MonthlyPaying;

        companion object {
            fun of(licenseInfo: LicenseInfo): CustomerSegment {
                val isFreeLicense = licenseInfo.saleLineItem.isFreeLicense
                return when {
                    isFreeLicense -> when (licenseInfo.sale.licensePeriod) {
                        LicensePeriod.Annual -> AnnualFree
                        LicensePeriod.Monthly -> MonthlyFree
                    }

                    else -> when (licenseInfo.sale.licensePeriod) {
                        LicensePeriod.Annual -> AnnualPaying
                        LicensePeriod.Monthly -> MonthlyPaying
                    }
                }
            }
        }
    }

    private data class MonthData(
        val year: Int,
        val month: Int,
        val customers: CustomerTracker<CustomerSegment>,
        val amounts: PaymentAmountTracker,
        val churnAnnualLicenses: ChurnProcessor<Int, CustomerInfo>,
        val churnAnnualPaidLicenses: ChurnProcessor<Int, CustomerInfo>,
        val churnMonthlyLicenses: ChurnProcessor<Int, CustomerInfo>,
        val churnMonthlyPaidLicenses: ChurnProcessor<Int, CustomerInfo>,
        val downloads: Long,
    ) {
        val isEmpty: Boolean
            get() {
                return amounts.totalAmountUSD == BigDecimal.ZERO
            }
    }

    private data class YearData(
        val year: Int,
        val churnAnnualPaidLicenses: ChurnProcessor<Int, CustomerInfo>,
        val churnMonthlyPaidLicenses: ChurnProcessor<Int, CustomerInfo>,
        val months: Map<Int, MonthData>
    ) {
        val isEmpty: Boolean
            get() {
                return months.isEmpty() || months.all { it.value.isEmpty }
            }
    }

    private var pluginId: PluginId? = null

    private lateinit var trialData: List<PluginTrial>
    private lateinit var downloadsMonthly: List<MonthlyDownload>
    private var downloadsTotal: Long = 0

    private val years = TreeMap<Int, YearData>(Comparator.reverseOrder())

    private val columnYearMonth = DataTableColumn("month", null)
    private val columnAmountTotalUSD = DataTableColumn("sales", "Total Sales", "num")
    private val columnAmountFeesUSD = DataTableColumn("sales", "Fees", "num")
    private val columnAmountPaidUSD = DataTableColumn("sales", "Paid", "num")
    private val columnActiveCustomers = DataTableColumn(
        "customer-count", "Cust.", "num", tooltip = "Customers at the end of month"
    )
    private val columnActiveCustomersPaying = DataTableColumn(
        "customer-count-paying", "Paying Cust.", "num", tooltip = "Paying customers at the end of month"
    )
    private val columnAnnualChurnPaid = DataTableColumn(
        "churn-annual-paid", "Annual (paid)", "num num-percentage", tooltip = "Churn of paid annual licenses"
    )
    private val columnMonthlyChurnPaid = DataTableColumn(
        "churn-monthly-paid", "Monthly (paid)", "num num-percentage", tooltip = "Churn of paid monthly licenses"
    )
    private val columnTrials = DataTableColumn(
        "trials", "Trials", "num ", tooltip = "Number of new trials at the end of the month"
    )
    private val columnDownloads = DataTableColumn(
        "downloads", "Downloads", "num ", tooltip = "Number of downloads in the month"
    )

    override val columns: List<DataTableColumn> = listOf(
        columnYearMonth,
        columnAmountTotalUSD,
        columnAmountFeesUSD,
        columnAmountPaidUSD,
        columnActiveCustomers,
        columnActiveCustomersPaying,
        columnAnnualChurnPaid,
        columnMonthlyChurnPaid,
        columnDownloads,
        columnTrials,
    )

    override fun init(data: PluginData) {
        this.pluginId = data.pluginId

        val now = YearMonthDay.now()
        this.trialData = data.trials ?: emptyList()
        this.downloadsMonthly = data.downloadsMonthly
        this.downloadsTotal = data.totalDownloads

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
                    PaymentAmountTracker(currentMonth),
                    createChurnProcessor(currentMonth),
                    createChurnProcessor(currentMonth),
                    createChurnProcessor(currentMonth),
                    createChurnProcessor(currentMonth),
                    downloadsMonthly
                        .firstOrNull { it.firstOfMonth.year == year && it.firstOfMonth.month == month }
                        ?.downloads
                        ?: 0L,
                )
            }

            // annual churn
            val activeTimeRange = YearMonthDayRange(YearMonthDay(year, 1, 1), minOf(now, YearMonthDay(year, 12, 31)))
            years[year] = YearData(year, createChurnProcessor(activeTimeRange), createChurnProcessor(activeTimeRange), months)
        }
    }

    override fun process(sale: PluginSale) {
        val monthData = years[sale.date.year]!!.months[sale.date.month]!!
        monthData.amounts.add(sale.date, sale.amountUSD)
    }

    override fun process(licenseInfo: LicenseInfo) {
        val customer = licenseInfo.sale.customer
        val licensePeriod = licenseInfo.sale.licensePeriod
        val customerSegment = CustomerSegment.of(licenseInfo)

        years.values.forEach { year ->
            val isPaidLicense = licenseInfo.isPaidLicense
            val isRenewal = licenseInfo.isRenewal

            year.churnAnnualPaidLicenses.processValue(
                customer.code,
                customer,
                licenseInfo.validity,
                licensePeriod == LicensePeriod.Annual && isPaidLicense,
                isRenewal
            )

            year.churnMonthlyPaidLicenses.processValue(
                customer.code,
                customer,
                licenseInfo.validity,
                licensePeriod == LicensePeriod.Monthly && isPaidLicense,
                isRenewal
            )

            year.months.values.forEach { month ->
                month.customers.add(customerSegment, licenseInfo)

                month.churnAnnualLicenses.processValue(
                    customer.code,
                    customer,
                    licenseInfo.validity,
                    licensePeriod == LicensePeriod.Annual,
                    isRenewal
                )
                month.churnAnnualPaidLicenses.processValue(
                    customer.code,
                    customer,
                    licenseInfo.validity,
                    licensePeriod == LicensePeriod.Annual && isPaidLicense,
                    isRenewal
                )

                month.churnMonthlyLicenses.processValue(
                    customer.code,
                    customer,
                    licenseInfo.validity,
                    licensePeriod == LicensePeriod.Monthly,
                    isRenewal
                )
                month.churnMonthlyPaidLicenses.processValue(
                    customer.code,
                    customer,
                    licenseInfo.validity,
                    licensePeriod == LicensePeriod.Monthly && isPaidLicense,
                    isRenewal
                )
            }
        }
    }

    override fun createSections(): List<DataTableSection> {
        val now = YearMonthDay.now()
        val pluginId = pluginId!!
        return years.entries
            .toMutableList()
            .dropLastWhile { it.value.isEmpty } // don't show empty years
            .map { (year, yearData) ->
                val rows = yearData.months.entries.map { (month, monthData) ->
                    val isCurrentMonth = now.year == year && now.month == month

                    val annualChurnPaid = monthData.churnAnnualPaidLicenses.getResult(LicensePeriod.Annual)
                    val annualChurnRatePaid = annualChurnPaid.getRenderedChurnRate(pluginId).takeUnless { isCurrentMonth }

                    val monthlyChurnPaid = monthData.churnMonthlyPaidLicenses.getResult(LicensePeriod.Monthly)
                    val monthlyChurnRatePaid = monthlyChurnPaid.getRenderedChurnRate(pluginId).takeUnless { isCurrentMonth }

                    val cssClass = when {
                        isCurrentMonth -> "today"
                        monthData.isEmpty -> "disabled"
                        else -> null
                    }

                    val annualCustomersFree = monthData.customers.segmentCustomerCount(AnnualFree)
                    val annualCustomersPaying = monthData.customers.segmentCustomerCount(AnnualPaying)
                    val monthlyCustomersPaying = monthData.customers.segmentCustomerCount(MonthlyPaying)

                    val totalCustomers = monthData.customers.totalCustomerCount
                    val totalCustomersPaying = monthData.customers.payingCustomerCount

                    val trialCount = trialData.count { it.date.year == year && it.date.month == month }
                    val downloadCount = monthData.downloads

                    SimpleDateTableRow(
                        values = mapOf(
                            columnYearMonth to String.format("%02d-%02d", year, month),
                            columnActiveCustomers to totalCustomers.toBigInteger(),
                            columnActiveCustomersPaying to totalCustomersPaying.toBigInteger(),
                            columnAmountTotalUSD to monthData.amounts.totalAmountUSD.withCurrency(Currency.USD),
                            columnAmountFeesUSD to monthData.amounts.feesAmountUSD.withCurrency(Currency.USD),
                            columnAmountPaidUSD to monthData.amounts.paidAmountUSD.withCurrency(Currency.USD),
                            columnAnnualChurnPaid to annualChurnRatePaid,
                            columnMonthlyChurnPaid to monthlyChurnRatePaid,
                            columnTrials to (trialCount.takeIf { it > 0 }?.toBigInteger() ?: "—"),
                            columnDownloads to (downloadCount.takeIf { it > 0 }?.toBigInteger() ?: "—"),
                        ),
                        tooltips = mapOf(
                            columnActiveCustomers to "$annualCustomersPaying annual (paying)" +
                                    "\n$annualCustomersFree annual (free)" +
                                    "\n$monthlyCustomersPaying monthly (paying)",
                            columnActiveCustomersPaying to "$annualCustomersPaying annual\n$monthlyCustomersPaying monthly",
                            columnAnnualChurnPaid to annualChurnPaid.churnRateTooltip,
                            columnMonthlyChurnPaid to monthlyChurnPaid.churnRateTooltip,
                        ),
                        cssClass = cssClass
                    )
                }

                val yearAnnualChurnResultPaid = yearData.churnAnnualPaidLicenses.getResult(LicensePeriod.Annual)
                val yearMonthlyChurnResultPaid = yearData.churnMonthlyPaidLicenses.getResult(LicensePeriod.Monthly)

                SimpleTableSection(
                    rows,
                    "$year",
                    footer = SimpleRowGroup(
                        SimpleDateTableRow(
                            values = mapOf(
                                columnAnnualChurnPaid to yearAnnualChurnResultPaid.getRenderedChurnRate(pluginId),
                                columnMonthlyChurnPaid to yearMonthlyChurnResultPaid.getRenderedChurnRate(pluginId),
                                columnDownloads to yearData.months.values.sumOf { it.downloads }.toBigInteger(),
                                columnTrials to trialData.count { it.date.year == year }.toBigInteger(),
                            ),
                            tooltips = mapOf(
                                columnAnnualChurnPaid to yearAnnualChurnResultPaid.churnRateTooltip,
                                columnMonthlyChurnPaid to yearMonthlyChurnResultPaid.churnRateTooltip,
                            )
                        )
                    )
                )
            }
    }

    private fun createChurnProcessor(timeRange: YearMonthDayRange): ChurnProcessor<CustomerId, CustomerInfo> {
        val churnDate = timeRange.end
        val activeDate = timeRange.start.add(0, 0, -1)
        val processor = MarketplaceChurnProcessor<CustomerInfo>(activeDate, churnDate)
        processor.init()
        return processor
    }
}
