/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.overview

import dev.ja.marketplace.churn.ChurnProcessor
import dev.ja.marketplace.churn.MarketplaceChurnProcessor
import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.Currency
import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.overview.OverviewTable.CustomerSegment.*
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.math.BigDecimal
import java.util.*

class OverviewTable(private val showCustomerChurn: Boolean = false) : SimpleDataTable("Overview", "overview", "table-striped tables-row"),
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
        val churnCustomersAnnual: ChurnProcessor<CustomerId, CustomerInfo>,
        val churnLicensesAnnual: ChurnProcessor<LicenseId, LicenseInfo>,
        val churnCustomersMonthly: ChurnProcessor<CustomerId, CustomerInfo>,
        val churnLicensesMonthly: ChurnProcessor<LicenseId, LicenseInfo>,
        val downloads: Long,
    ) {
        val isEmpty: Boolean
            get() {
                return amounts.totalAmountUSD == BigDecimal.ZERO
            }
    }

    private data class YearData(
        val year: Int,
        val churnCustomersAnnual: ChurnProcessor<CustomerId, CustomerInfo>,
        val churnLicensesAnnual: ChurnProcessor<LicenseId, LicenseInfo>,
        val churnCustomersMonthly: ChurnProcessor<CustomerId, CustomerInfo>,
        val churnLicensesMonthly: ChurnProcessor<LicenseId, LicenseInfo>,
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
    private val columnCustomerChurnAnnual = DataTableColumn(
        "churn-annual-paid", "Cust. churn (annual)", "num num-percentage", tooltip = "Churn of customers with annual licenses"
    )
    private val columnLicenseChurnAnnual = DataTableColumn(
        "churn-annual-paid", "Churn (annual)", "num num-percentage", tooltip = "Churn of paid annual licenses"
    )
    private val columnCustomerChurnMonthly = DataTableColumn(
        "churn-monthly-paid", "Cust. churn (monthly)", "num num-percentage", tooltip = "Churn of customers with paid monthly licenses"
    )
    private val columnLicenseChurnMonthly = DataTableColumn(
        "churn-monthly-paid", "Churn (monthly)", "num num-percentage", tooltip = "Churn of paid monthly licenses"
    )
    private val columnTrials = DataTableColumn(
        "trials", "Trials", "num ", tooltip = "Number of new trials at the end of the month"
    )
    private val columnDownloads = DataTableColumn(
        "downloads", "↓", "num ", tooltip = "Number of downloads in the month"
    )

    override val columns: List<DataTableColumn> = listOfNotNull(
        columnYearMonth,
        columnAmountTotalUSD,
        columnAmountFeesUSD,
        columnAmountPaidUSD,
        columnActiveCustomers,
        columnActiveCustomersPaying,
        columnLicenseChurnAnnual,
        columnLicenseChurnMonthly,
        columnCustomerChurnAnnual.takeIf { showCustomerChurn },
        columnCustomerChurnMonthly.takeIf { showCustomerChurn },
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
                    createCustomerChurnProcessor(currentMonth),
                    createLicenseChurnProcessor(currentMonth),
                    createCustomerChurnProcessor(currentMonth),
                    createLicenseChurnProcessor(currentMonth),
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

            year.churnCustomersAnnual.processValue(
                customer.code,
                customer,
                licenseInfo.validity,
                licensePeriod == LicensePeriod.Annual && isPaidLicense,
                isRenewal
            )
            year.churnLicensesAnnual.processValue(
                licenseInfo.id,
                licenseInfo,
                licenseInfo.validity,
                licensePeriod == LicensePeriod.Annual && isPaidLicense,
                isRenewal
            )

            year.churnCustomersMonthly.processValue(
                customer.code,
                customer,
                licenseInfo.validity,
                licensePeriod == LicensePeriod.Monthly && isPaidLicense,
                isRenewal
            )
            year.churnLicensesMonthly.processValue(
                licenseInfo.id,
                licenseInfo,
                licenseInfo.validity,
                licensePeriod == LicensePeriod.Monthly && isPaidLicense,
                isRenewal
            )

            year.months.values.forEach { month ->
                month.customers.add(customerSegment, licenseInfo)

                month.churnCustomersAnnual.processValue(
                    customer.code,
                    customer,
                    licenseInfo.validity,
                    licensePeriod == LicensePeriod.Annual && isPaidLicense,
                    isRenewal
                )
                month.churnLicensesAnnual.processValue(
                    licenseInfo.id,
                    licenseInfo,
                    licenseInfo.validity,
                    licensePeriod == LicensePeriod.Annual && isPaidLicense,
                    isRenewal
                )

                month.churnCustomersMonthly.processValue(
                    customer.code,
                    customer,
                    licenseInfo.validity,
                    licensePeriod == LicensePeriod.Monthly && isPaidLicense,
                    isRenewal
                )
                month.churnLicensesMonthly.processValue(
                    licenseInfo.id,
                    licenseInfo,
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

                    val annualLicenseChurn = monthData.churnLicensesAnnual.getResult(LicensePeriod.Annual).takeUnless { isCurrentMonth }
                    val annualLicenseChurnRate = annualLicenseChurn?.getRenderedChurnRate(pluginId)

                    val monthlyLicenseChurn = monthData.churnLicensesMonthly.getResult(LicensePeriod.Monthly).takeUnless { isCurrentMonth }
                    val monthlyLicenseChurnRate = monthlyLicenseChurn?.getRenderedChurnRate(pluginId)

                    val annualCustomerChurn = monthData.churnCustomersAnnual.getResult(LicensePeriod.Annual).takeUnless { isCurrentMonth }
                    val annualCustomerChurnRate = annualCustomerChurn?.getRenderedChurnRate(pluginId)

                    val monthlyCustomerChurn = monthData.churnCustomersMonthly.getResult(LicensePeriod.Monthly).takeUnless { isCurrentMonth }
                    val monthlyCustomerChurnRate = monthlyCustomerChurn?.getRenderedChurnRate(pluginId)

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
                            columnLicenseChurnAnnual to annualLicenseChurnRate,
                            columnLicenseChurnMonthly to monthlyLicenseChurnRate,
                            columnCustomerChurnMonthly to monthlyCustomerChurnRate,
                            columnCustomerChurnAnnual to annualCustomerChurnRate,
                            columnTrials to (trialCount.takeIf { it > 0 }?.toBigInteger() ?: "—"),
                            columnDownloads to (downloadCount.takeIf { it > 0 }?.toBigInteger() ?: "—"),
                        ),
                        tooltips = mapOf(
                            columnActiveCustomers to "$annualCustomersPaying annual (paying)" +
                                    "\n$annualCustomersFree annual (free)" +
                                    "\n$monthlyCustomersPaying monthly (paying)",
                            columnActiveCustomersPaying to "$annualCustomersPaying annual\n$monthlyCustomersPaying monthly",
                            columnLicenseChurnAnnual to annualLicenseChurn?.churnRateTooltip,
                            columnLicenseChurnMonthly to monthlyLicenseChurn?.churnRateTooltip,
                            columnCustomerChurnAnnual to annualCustomerChurn?.churnRateTooltip,
                            columnCustomerChurnMonthly to monthlyCustomerChurn?.churnRateTooltip,
                        ),
                        cssClass = cssClass
                    )
                }

                val yearCustomerChurnAnnual = yearData.churnCustomersAnnual.getResult(LicensePeriod.Annual)
                val yearLicenseChurnAnnual = yearData.churnLicensesAnnual.getResult(LicensePeriod.Annual)

                val yearCustomerChurnMonthly = yearData.churnCustomersMonthly.getResult(LicensePeriod.Monthly)
                val yearLicenseChurnMonthly = yearData.churnLicensesMonthly.getResult(LicensePeriod.Monthly)

                SimpleTableSection(
                    rows,
                    "$year",
                    footer = SimpleRowGroup(
                        SimpleDateTableRow(
                            values = mapOf(
                                columnLicenseChurnAnnual to yearLicenseChurnAnnual.getRenderedChurnRate(pluginId),
                                columnLicenseChurnMonthly to yearLicenseChurnMonthly.getRenderedChurnRate(pluginId),
                                columnCustomerChurnAnnual to yearCustomerChurnAnnual.getRenderedChurnRate(pluginId),
                                columnCustomerChurnMonthly to yearCustomerChurnMonthly.getRenderedChurnRate(pluginId),
                                columnDownloads to yearData.months.values.sumOf { it.downloads }.toBigInteger(),
                                columnTrials to trialData.count { it.date.year == year }.toBigInteger(),
                            ),
                            tooltips = mapOf(
                                columnLicenseChurnAnnual to yearLicenseChurnAnnual.churnRateTooltip,
                                columnLicenseChurnMonthly to yearLicenseChurnMonthly.churnRateTooltip,
                                columnCustomerChurnAnnual to yearCustomerChurnAnnual.churnRateTooltip,
                                columnCustomerChurnMonthly to yearCustomerChurnMonthly.churnRateTooltip,
                            )
                        )
                    )
                )
            }
    }

    private fun createCustomerChurnProcessor(timeRange: YearMonthDayRange): ChurnProcessor<CustomerId, CustomerInfo> {
        val churnDate = timeRange.end
        val activeDate = timeRange.start.add(0, 0, -1)
        val processor = MarketplaceChurnProcessor<CustomerId, CustomerInfo>(activeDate, churnDate, ::HashSet)
        processor.init()
        return processor
    }

    private fun createLicenseChurnProcessor(timeRange: YearMonthDayRange): ChurnProcessor<LicenseId, LicenseInfo> {
        val churnDate = timeRange.end
        val activeDate = timeRange.start.add(0, 0, -1)
        val processor = MarketplaceChurnProcessor<LicenseId, LicenseInfo>(activeDate, churnDate, ::HashSet)
        processor.init()
        return processor
    }
}
