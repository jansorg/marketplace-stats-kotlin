package ja.dev.marketplace.data.overview

import ja.dev.marketplace.churn.ChurnProcessor
import ja.dev.marketplace.churn.SimpleChurnProcessor
import ja.dev.marketplace.client.*
import ja.dev.marketplace.client.Currency
import ja.dev.marketplace.data.*
import java.math.BigDecimal
import java.util.*

class OverviewTable : SimpleDataTable("Overview", "overview", "table-striped"), MarketplaceDataSink {
    private data class MonthData(
        val year: Int,
        val month: Int,
        val customers: CustomerTracker<LicensePeriod>,
        val churnAnnualLicenses: ChurnProcessor<Int, CustomerInfo>,
        val churnMonthlyLicenses: ChurnProcessor<Int, CustomerInfo>,
        var amountUSD: Amount = BigDecimal.ZERO,
    ) {
        val isEmpty: Boolean
            get() {
                return amountUSD.toDouble() == 0.0
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

    private val years = TreeMap<Int, YearData>()

    private val columnYearMonth = DataTableColumn("month", null)
    private val columnAmountUSD = DataTableColumn("sales", "Sales", "num")
    private val columnActiveCustomers = DataTableColumn(
        "customer-count", "Cust.", "num", tooltip = "Customers at the end of month"
    )
    private val columnAnnualChurn = DataTableColumn(
        "churn-annual", "Annual", "num num-percentage", tooltip = "Churn of annual licenses"
    )
    private val columnMonthlyChurn = DataTableColumn(
        "churn-monthly", "Monthly", "num num-percentage", tooltip = "Churn of monthly licenses"
    )

    override val columns: List<DataTableColumn> = listOf(
        columnYearMonth,
        columnAmountUSD,
        columnActiveCustomers,
        columnAnnualChurn,
        columnMonthlyChurn
    )

    override fun init() {
        val now = YearMonthDay.now()
        for (year in Marketplace.Birthday.year..now.year) {
            val monthRange = when (year) {
                now.year -> 1..now.month
                else -> 1..12
            }

            val months = monthRange.associateWith { month ->
                val currentMonth = YearMonthDayRange.ofMonth(year, month)
                val churnDate = currentMonth.end
                val activeDate = churnDate.add(0, -1, 0)
                val churnAnnualCustomers = SimpleChurnProcessor<CustomerInfo>(activeDate, churnDate)
                churnAnnualCustomers.init()

                val churnMonthlyCustomers = SimpleChurnProcessor<CustomerInfo>(activeDate, churnDate)
                churnMonthlyCustomers.init()

                MonthData(year, month, CustomerTracker(currentMonth), churnAnnualCustomers, churnMonthlyCustomers)
            }

            // annual churn
            val yearRange = YearMonthDayRange.ofYear(year)
            val previousYear = YearMonthDayRange.ofYear(year - 1)
            val actualYearRange = when (now) {
                in yearRange -> YearMonthDayRange(YearMonthDay(year, 1, 1), now)
                else -> yearRange
            }

            val churnAnnualCustomers = SimpleChurnProcessor<CustomerInfo>(previousYear.end, actualYearRange.end)
            churnAnnualCustomers.init()

            val churnMonthlyCustomers = SimpleChurnProcessor<CustomerInfo>(previousYear.end, actualYearRange.end)
            churnMonthlyCustomers.init()

            years[year] = YearData(year, churnAnnualCustomers, churnMonthlyCustomers, months)
        }
    }

    override fun process(sale: PluginSale) {
        years[sale.date.year]!!.months[sale.date.month]!!.amountUSD += sale.amountUSD
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

                        SimpleDateTableRow(
                            values = mapOf(
                                columnYearMonth to String.format("%02d-%02d", year, month),
                                columnActiveCustomers to totalCustomers,
                                columnAmountUSD to monthData.amountUSD.withCurrency(Currency.USD),
                                columnAnnualChurn to annualChurnRate,
                                columnMonthlyChurn to monthlyChurnRate,
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
                                    columnMonthlyChurn to yearMonthlyChurnResult.renderedChurnRate
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
