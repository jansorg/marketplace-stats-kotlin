/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customerType

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import java.math.BigDecimal
import java.util.*

/**
 * The sales by customer type. The chart of the share of the sales shows the share of each customer type in each month.
 */
class CustomerTypeTable(
    private val today: YearMonthDay = YearMonthDay.now(),
) : SimpleDataTable("Customer Type", "customer-type"), MarketplaceDataSink {
    private val columnType = DataTableColumn("customer-type", null)
    private val columnAmount = DataTableColumn("amount", null, "num", chartTitle = "Total Sales")
    private val columnPercentage = DataTableColumn(
        "percentage", "% of Sales", "num num-percentage",
        chartTitle = "% of Sales per Month", chartMeasure = ChartMeasure.Ratio, rowHistoryPeriod = ChartPeriod.Month,
    )

    private lateinit var totalAmount: MonetaryAmountTracker
    private val customerTypes = TreeMap<CustomerType, MonetaryAmountTracker>()

    // the sales of each month by its first day, of all customers and of each customer type
    private val monthlyTotals = TreeMap<YearMonthDay, MonetaryAmountTracker>()
    private val monthlyCustomerTypes = HashMap<Pair<CustomerType, YearMonthDay>, MonetaryAmountTracker>()

    override val columns: List<DataTableColumn> = listOf(columnType, columnAmount, columnPercentage)

    override suspend fun init(data: PluginData) {
        super.init(data)

        totalAmount = MonetaryAmountTracker(data.exchangeRates)
    }

    override suspend fun process(sale: PluginSale) {
        totalAmount.add(sale.date, sale.amountUSD, sale.amount)

        val tracker = customerTypes.computeIfAbsent(sale.customer.type) { MonetaryAmountTracker(exchangeRates) }
        tracker.add(sale.date, sale.amountUSD, sale.amount)

        val month = YearMonthDay.firstOfMonth(sale.date.year, sale.date.month)
        monthlyTotals.computeIfAbsent(month) { MonetaryAmountTracker(exchangeRates) }.add(sale.date, sale.amountUSD, sale.amount)
        monthlyCustomerTypes.computeIfAbsent(sale.customer.type to month) { MonetaryAmountTracker(exchangeRates) }
            .add(sale.date, sale.amountUSD, sale.amount)
    }

    /**
     * @return The share of the customer type of the sales of each month, the current month may still change.
     *  Months without sales, e.g. with free licenses only, have no share.
     */
    private suspend fun monthlyShares(customerType: CustomerType): Map<YearMonthDay, Any?> {
        val currentMonth = YearMonthDay.firstOfMonth(today.year, today.month)
        return monthlyTotals.entries
            .map { (month, total) -> month to total.getTotalAmount() }
            .filterNot { (_, total) -> total.isZero }
            .associate { (month, total) ->
                val share = monthlyCustomerTypes[customerType to month]?.let { PercentageValue.of(it.getTotalAmount(), total) }
                    ?: PercentageValue(BigDecimal.ZERO)
                month to if (month >= currentMonth) IncompleteValue(share) else share
            }
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        // ignored
    }

    override suspend fun createSections(): List<DataTableSection> {
        val totalAmount = this.totalAmount.getTotalAmount()

        val rows = customerTypes.entries.map { (customerType, amount) ->
            SimpleDateTableRow(
                values = mapOf(
                    columnType to customerType,
                    columnAmount to amount.getTotalAmount(),
                    columnPercentage to PercentageValue.of(amount.getTotalAmount(), totalAmount),
                ),
                chartHistories = mapOf(columnPercentage to monthlyShares(customerType)),
            )
        }

        return listOf(
            SimpleTableSection(
                rows,
                footer = SimpleTableSection(
                    SimpleDateTableRow(
                        columnAmount to totalAmount,
                        columnPercentage to PercentageValue.ONE_HUNDRED,
                    )
                )
            )
        )
    }
}