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
        val churnAnnualLicenses: ChurnProcessor<Int, CustomerInfo>,
        val churnAnnualPaidLicenses: ChurnProcessor<Int, CustomerInfo>,
        val churnMonthlyLicenses: ChurnProcessor<Int, CustomerInfo>,
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
    private val columnAnnualChurn = DataTableColumn(
        "churn-annual", "Annual", "num num-percentage", tooltip = "Churn of annual licenses"
    )
    private val columnAnnualChurnPaid = DataTableColumn(
        "churn-annual-paid", "Annual (paid)", "num num-percentage", tooltip = "Churn of paid annual licenses"
    )
    private val columnMonthlyChurn = DataTableColumn(
        "churn-monthly", "Monthly", "num num-percentage", tooltip = "Churn of monthly licenses"
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
                val churnDate = currentMonth.end
                val activeDate = churnDate.add(0, -1, 0)

                val activeCustomerRange = when {
                    YearMonthDay.now() in currentMonth -> currentMonth.copy(end = YearMonthDay.now())
                    else -> currentMonth
                }

                MonthData(
                    year,
                    month,
                    CustomerTracker(activeCustomerRange),
                    PaymentAmountTracker(currentMonth),
                    createChurnProcessor(activeDate, churnDate),
                    createChurnProcessor(activeDate, churnDate),
                    createChurnProcessor(activeDate, churnDate),
                    createChurnProcessor(activeDate, churnDate),
                    downloadsMonthly
                        .firstOrNull { it.firstOfMonth.year == year && it.firstOfMonth.month == month }
                        ?.downloads
                        ?: 0L,
                )
            }

            // annual churn
            val churnDate = when (now.year) {
                year -> now
                else -> YearMonthDay(year, 12, 31)
            }
            val activeDate = YearMonthDay(year - 1, 12, 31)

            years[year] = YearData(
                year,
                createChurnProcessor(activeDate, churnDate),
                createChurnProcessor(activeDate, churnDate),
                createChurnProcessor(activeDate, churnDate),
                createChurnProcessor(activeDate, churnDate),
                months
            )
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

            year.churnAnnualLicenses.processValue(
                customer.code,
                customer,
                licenseInfo.validity,
                licensePeriod == LicensePeriod.Annual,
                isRenewal
            )
            year.churnAnnualPaidLicenses.processValue(
                customer.code,
                customer,
                licenseInfo.validity,
                licensePeriod == LicensePeriod.Annual && isPaidLicense,
                isRenewal
            )

            year.churnMonthlyLicenses.processValue(
                customer.code,
                customer,
                licenseInfo.validity,
                licensePeriod == LicensePeriod.Monthly,
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
        return years.entries
            .toMutableList()
            .dropLastWhile { it.value.isEmpty } // don't show empty years
            .map { (year, yearData) ->
                val rows = yearData.months.entries.map { (month, monthData) ->
                    val isCurrentMonth = now.year == year && now.month == month

                    val annualChurn = monthData.churnAnnualLicenses.getResult(LicensePeriod.Annual)
                    val annualChurnRate = annualChurn.getRenderedChurnRate(pluginId!!).takeIf { !isCurrentMonth }

                    val annualChurnPaid = monthData.churnAnnualPaidLicenses.getResult(LicensePeriod.Annual)
                    val annualChurnRatePaid =
                        annualChurnPaid.getRenderedChurnRate(pluginId!!).takeIf { !isCurrentMonth }

                    val monthlyChurn = monthData.churnMonthlyLicenses.getResult(LicensePeriod.Monthly)
                    val monthlyChurnRate = monthlyChurn.getRenderedChurnRate(pluginId!!).takeIf { !isCurrentMonth }

                    val monthlyChurnPaid = monthData.churnMonthlyPaidLicenses.getResult(LicensePeriod.Monthly)
                    val monthlyChurnRatePaid =
                        monthlyChurnPaid.getRenderedChurnRate(pluginId!!).takeIf { !isCurrentMonth }

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
                            columnAnnualChurn to annualChurnRate,
                            columnAnnualChurnPaid to annualChurnRatePaid,
                            columnMonthlyChurn to monthlyChurnRate,
                            columnMonthlyChurnPaid to monthlyChurnRatePaid,
                            columnTrials to (trialCount.takeIf { it > 0 }?.toBigInteger() ?: "—"),
                            columnDownloads to (downloadCount.takeIf { it > 0 }?.toBigInteger() ?: "—"),
                        ),
                        tooltips = mapOf(
                            columnActiveCustomers to "$annualCustomersPaying annual (paying)" +
                                    "\n$annualCustomersFree annual (free)" +
                                    "\n$monthlyCustomersPaying monthly (paying)",
                            columnActiveCustomersPaying to "$annualCustomersPaying annual" +
                                    "\n$monthlyCustomersPaying monthly",
                            columnAnnualChurn to annualChurn.churnRateTooltip,
                            columnAnnualChurnPaid to annualChurnPaid.churnRateTooltip,
                            columnMonthlyChurn to monthlyChurn.churnRateTooltip,
                            columnMonthlyChurnPaid to monthlyChurnPaid.churnRateTooltip,
                        ),
                        cssClass = cssClass
                    )
                }

                val yearAnnualChurnResult = yearData.churnAnnualLicenses.getResult(LicensePeriod.Annual)
                val yearAnnualChurnResultPaid = yearData.churnAnnualPaidLicenses.getResult(LicensePeriod.Annual)

                val yearMonthlyChurnResult = yearData.churnMonthlyLicenses.getResult(LicensePeriod.Monthly)
                val yearMonthlyChurnResultPaid = yearData.churnMonthlyPaidLicenses.getResult(LicensePeriod.Monthly)

                SimpleTableSection(
                    rows,
                    "$year",
                    footer = SimpleRowGroup(
                        SimpleDateTableRow(
                            values = mapOf(
                                columnAnnualChurn to yearAnnualChurnResult.getRenderedChurnRate(pluginId!!),
                                columnAnnualChurnPaid to yearAnnualChurnResultPaid.getRenderedChurnRate(pluginId!!),
                                columnMonthlyChurn to yearMonthlyChurnResult.getRenderedChurnRate(pluginId!!),
                                columnMonthlyChurnPaid to yearMonthlyChurnResultPaid.getRenderedChurnRate(pluginId!!),
                                columnDownloads to yearData.months.values.sumOf { it.downloads }.toBigInteger(),
                                columnTrials to trialData.count { it.date.year == year }.toBigInteger(),
                            ),
                            tooltips = mapOf(
                                columnAnnualChurn to yearAnnualChurnResult.churnRateTooltip,
                                columnAnnualChurnPaid to yearAnnualChurnResultPaid.churnRateTooltip,
                                columnMonthlyChurn to yearMonthlyChurnResult.churnRateTooltip,
                                columnMonthlyChurnPaid to yearMonthlyChurnResultPaid.churnRateTooltip,
                            )
                        )
                    )
                )
            }
    }

    private fun createChurnProcessor(
        activeDate: YearMonthDay,
        churnDate: YearMonthDay
    ): ChurnProcessor<CustomerId, CustomerInfo> {
        val processor = MarketplaceChurnProcessor<CustomerInfo>(activeDate, churnDate)
        processor.init()
        return processor
    }
}
