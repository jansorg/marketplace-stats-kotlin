/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.overview

import dev.ja.marketplace.churn.ChurnProcessor
import dev.ja.marketplace.churn.SimpleChurnProcessor
import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.Currency
import dev.ja.marketplace.data.*
import java.math.BigDecimal
import java.util.*

class OverviewTable(private val graceTimeDays: Int = 7) : SimpleDataTable("Overview", "overview", "table-striped"),
    MarketplaceDataSink {
    private data class MonthData(
        val year: Int,
        val month: Int,
        val customers: CustomerTracker<LicensePeriod>,
        val amounts: PaymentAmountTracker,
        val churnAnnualLicenses: ChurnProcessor<Int, CustomerInfo>,
        val churnMonthlyLicenses: ChurnProcessor<Int, CustomerInfo>,
    ) {
        val isEmpty: Boolean
            get() {
                return amounts.totalAmountUSD == BigDecimal.ZERO
            }
    }

    private data class YearData(
        val year: Int,
        val churnAnnualLicenses: ChurnProcessor<Int, CustomerInfo>,
        val churnMonthlyLicenses: ChurnProcessor<Int, CustomerInfo>,
        val months: Map<Int, MonthData>
    ) {
        val isEmpty: Boolean
            get() {
                return months.isEmpty() || months.all { it.value.isEmpty }
            }
    }

    private lateinit var trialData: List<PluginTrial>

    private val years = TreeMap<Int, YearData>()

    private val columnYearMonth = DataTableColumn("month", null)
    private val columnAmountTotalUSD = DataTableColumn("sales", "Total Sales", "num")
    private val columnAmountFeesUSD = DataTableColumn("sales", "Fees", "num")
    private val columnAmountPaidUSD = DataTableColumn("sales", "Paid", "num")
    private val columnActiveCustomers = DataTableColumn(
        "customer-count", "Cust.", "num", tooltip = "Customers at the end of month"
    )
    private val columnAnnualChurn = DataTableColumn(
        "churn-annual", "Annual", "num num-percentage", tooltip = "Churn of annual licenses"
    )
    private val columnMonthlyChurn = DataTableColumn(
        "churn-monthly", "Monthly", "num num-percentage", tooltip = "Churn of monthly licenses"
    )
    private val columnTrials = DataTableColumn(
        "trials", "Trials", "num ", tooltip = "Number of new trials at the end of the month"
    )

    override val columns: List<DataTableColumn> = listOf(
        columnYearMonth,
        columnAmountTotalUSD,
        columnAmountFeesUSD,
        columnAmountPaidUSD,
        columnActiveCustomers,
        columnAnnualChurn,
        columnMonthlyChurn,
        columnTrials,
    )

    override fun init(data: PluginData) {
        val now = YearMonthDay.now()
        this.trialData = data.trials

        for (year in Marketplace.Birthday.year..now.year) {
            val monthRange = when (year) {
                now.year -> 1..now.month
                else -> 1..12
            }

            val months = monthRange.associateWith { month ->
                val currentMonth = YearMonthDayRange.ofMonth(year, month)
                val churnDate = currentMonth.end
                val activeDate = churnDate.add(0, -1, 0)
                val churnAnnualCustomers = SimpleChurnProcessor<CustomerInfo>(activeDate, churnDate, graceTimeDays)
                churnAnnualCustomers.init()

                val churnMonthlyCustomers = SimpleChurnProcessor<CustomerInfo>(activeDate, churnDate, graceTimeDays)
                churnMonthlyCustomers.init()

                val activeCustomerRange = when {
                    YearMonthDay.now() in currentMonth -> currentMonth.copy(end = YearMonthDay.now())
                    else -> currentMonth
                }
                MonthData(
                    year,
                    month,
                    CustomerTracker(activeCustomerRange),
                    PaymentAmountTracker(currentMonth),
                    churnAnnualCustomers,
                    churnMonthlyCustomers
                )
            }

            // annual churn
            val churnDate = when (now.year) {
                year -> now
                else -> YearMonthDay(year, 12, 31)
            }
            val activeDate = YearMonthDay(year - 1, 12, 31)

            val churnAnnualCustomers = SimpleChurnProcessor<CustomerInfo>(activeDate, churnDate, graceTimeDays)
            churnAnnualCustomers.init()

            val churnMonthlyCustomers = SimpleChurnProcessor<CustomerInfo>(activeDate, churnDate, graceTimeDays)
            churnMonthlyCustomers.init()

            years[year] = YearData(year, churnAnnualCustomers, churnMonthlyCustomers, months)
        }
    }

    override fun process(sale: PluginSale) {
        val monthData = years[sale.date.year]!!.months[sale.date.month]!!
        monthData.amounts.add(sale.date, sale.amountUSD)
    }

    override fun process(licenseInfo: LicenseInfo) {
        val customer = licenseInfo.sale.customer
        val licensePeriod = licenseInfo.sale.licensePeriod

        years.values.forEach { year ->
            year.churnAnnualLicenses.processValue(
                customer.code,
                customer,
                licenseInfo.validity,
                licensePeriod == LicensePeriod.Annual
            )
            year.churnMonthlyLicenses.processValue(
                customer.code,
                customer,
                licenseInfo.validity,
                licensePeriod == LicensePeriod.Monthly
            )

            year.months.values.forEach { month ->
                month.customers.add(licensePeriod, licenseInfo)

                month.churnAnnualLicenses.processValue(
                    customer.code,
                    customer,
                    licenseInfo.validity,
                    licensePeriod == LicensePeriod.Annual
                )
                month.churnMonthlyLicenses.processValue(
                    customer.code,
                    customer,
                    licenseInfo.validity,
                    licensePeriod == LicensePeriod.Monthly
                )
            }
        }
    }

    override val sections: List<DataTableSection>
        get() {
            val now = YearMonthDay.now()

            return years.entries.dropWhile { it.value.isEmpty }
                .map { (year, yearData) ->
                    val rows = yearData.months.entries.map { (month, monthData) ->
                        val isCurrentMonth = now.year == year && now.month == month

                        val annualChurn = monthData.churnAnnualLicenses.getResult()
                        val annualChurnRate = annualChurn.renderedChurnRate?.takeIf { !isCurrentMonth }

                        val monthlyChurn = monthData.churnMonthlyLicenses.getResult()
                        val monthlyChurnRate = monthlyChurn.renderedChurnRate?.takeIf { !isCurrentMonth }

                        val cssClass = when {
                            isCurrentMonth -> "today"
                            else -> null
                        }

                        val annualCustomers = monthData.customers.segmentCustomerCount(LicensePeriod.Annual)
                        val monthlyCustomers = monthData.customers.segmentCustomerCount(LicensePeriod.Monthly)
                        val totalCustomers = monthData.customers.totalCustomerCount

                        val trialCount = trialData.count { it.date.year == year && it.date.month == month }

                        SimpleDateTableRow(
                            values = mapOf(
                                columnYearMonth to String.format("%02d-%02d", year, month),
                                columnActiveCustomers to totalCustomers,
                                columnAmountTotalUSD to monthData.amounts.totalAmountUSD.withCurrency(Currency.USD),
                                columnAmountFeesUSD to monthData.amounts.feesAmountUSD.withCurrency(Currency.USD),
                                columnAmountPaidUSD to monthData.amounts.paidAmountUSD.withCurrency(Currency.USD),
                                columnAnnualChurn to annualChurnRate,
                                columnMonthlyChurn to monthlyChurnRate,
                                columnTrials to trialCount.let { if (it > 0) it else "—" },
                            ),
                            tooltips = mapOf(
                                columnActiveCustomers to "$annualCustomers annual, $monthlyCustomers monthly",
                                columnAnnualChurn to annualChurn.churnRateTooltip,
                                columnMonthlyChurn to monthlyChurn.churnRateTooltip,
                            ),
                            cssClass = cssClass
                        )
                    }

                    val yearAnnualChurnResult = yearData.churnAnnualLicenses.getResult()
                    val yearMonthlyChurnResult = yearData.churnMonthlyLicenses.getResult()
                    SimpleTableSection(
                        rows,
                        "$year",
                        footer = SimpleRowGroup(
                            SimpleDateTableRow(
                                values = mapOf(
                                    columnAnnualChurn to yearAnnualChurnResult.renderedChurnRate,
                                    columnMonthlyChurn to yearMonthlyChurnResult.renderedChurnRate,
                                    columnTrials to trialData.count { it.date.year == year },
                                ),
                                tooltips = mapOf(
                                    columnAnnualChurn to yearAnnualChurnResult.churnRateTooltip,
                                    columnMonthlyChurn to yearMonthlyChurnResult.churnRateTooltip,
                                )
                            )
                        )
                    )
                }
        }
}
