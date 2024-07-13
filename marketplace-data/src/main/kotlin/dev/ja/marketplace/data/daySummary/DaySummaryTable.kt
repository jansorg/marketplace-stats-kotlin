/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.daySummary

import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import dev.ja.marketplace.util.sortValue
import java.math.BigInteger
import javax.money.MonetaryAmount

class DaySummaryTable(
    val date: YearMonthDay,
    title: String
) : SimpleDataTable(title, cssClass = "small table-striped"), MarketplaceDataSink {
    private lateinit var totalSales: MonetaryAmountTracker
    private val sales = mutableMapOf<Pair<CustomerType, LicensePeriod>, MonetaryAmountTracker>()
    private lateinit var trials: List<PluginTrial>

    private val columnSubscriptionType = DataTableColumn("sales-subscription", null, "col-right")
    private val columnCustomerType = DataTableColumn("sales-type", null, "col-right")
    private val columnAmount = DataTableColumn("sales-amount", "Sales", "num", preSorted = AriaSortOrder.Descending)

    private val columnTrialCountry = DataTableColumn("trial-country", null, "col-right", columnSpan = 2)
    private val columnTrialCount = DataTableColumn("trial-count", "Trials", "num", preSorted = AriaSortOrder.Descending)

    override val columns: List<DataTableColumn> = listOf(columnSubscriptionType, columnCustomerType, columnAmount)

    override suspend fun init(data: PluginData) {
        super.init(data)

        this.totalSales = MonetaryAmountTracker(data.exchangeRates)

        for (type in CustomerType.entries) {
            for (period in LicensePeriod.entries) {
                sales[type to period] = MonetaryAmountTracker(exchangeRates)
            }
        }

        trials = data.getTrials()
            ?.filter { it.date == date }
            ?.sortedBy { it.customer.country }
            ?: emptyList()
    }

    override suspend fun process(sale: PluginSale) {
        if (sale.date == date) {
            totalSales.add(sale.date, sale.amountUSD, sale.amount)

            val tracker = sales[sale.customer.type to sale.licensePeriod]
                ?: throw IllegalStateException("Unable to find sales for $sale")
            tracker.add(sale.date, sale.amountUSD, sale.amount)
        }
    }

    override suspend fun process(licenseInfo: LicenseInfo) {
        // ignored
    }

    override suspend fun createSections(): List<DataTableSection> {
        val salesTable = sales.map {
            SimpleDateTableRow(
                columnCustomerType to it.key.first,
                columnSubscriptionType to it.key.second,
                columnAmount to it.value.getTotalAmount()
            )
        }.sortedByDescending {
            (it.values[columnAmount] as MonetaryAmount).sortValue()
        }

        val trialRows = trials
            .groupBy { it.customer.type }
            .mapValues { it.value.groupBy { it.customer.country } }
            .flatMap { (_, countryTrials) ->
                countryTrials.map { (country, trials) ->
                    SimpleDateTableRow(
                        columnTrialCountry to (country.takeIf(String::isNotEmpty) ?: NoValue),
                        columnTrialCount to trials.size.toBigInteger()
                    )
                }
            }
            .sortedByDescending { it.values[columnTrialCount] as BigInteger }

        return listOf(
            SimpleTableSection(
                rows = salesTable,
                columns = listOf(columnSubscriptionType, columnCustomerType, columnAmount),
                footer = SimpleRowGroup(SimpleDateTableRow(columnAmount to totalSales.getTotalAmount()))
            ),
            SimpleTableSection(
                title = "",
                rows = trialRows,
                columns = listOf(columnTrialCountry, columnTrialCount),
                footer = SimpleRowGroup(SimpleDateTableRow(columnTrialCount to trials.size.toBigInteger()))
            )
        )
    }
}