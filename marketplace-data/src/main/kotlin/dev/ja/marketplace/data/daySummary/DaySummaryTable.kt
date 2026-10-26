/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.daySummary

import dev.ja.marketplace.client.*
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginTrial
import dev.ja.marketplace.data.*
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import java.math.BigInteger

/**
 * Sales by license period and customer type and trials by country of the days of [dateRange].
 * Only the license periods offered for the plugin and the periods of the sales of the range are shown.
 */
class DaySummaryTable(
    val dateRange: YearMonthDayRange,
    title: String
) : SimpleDataTable(title, cssClass = "small table-striped"), MarketplaceDataSink {
    constructor(date: YearMonthDay, title: String) : this(YearMonthDayRange.of(date), title)

    private lateinit var totalSales: MonetaryAmountTracker
    private val sales = linkedMapOf<Pair<CustomerType, LicensePeriod>, MonetaryAmountTracker>()
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

        val periods = data.getOfferedLicensePeriods()?.let { offered ->
            val periodsOfSales = data.getSales()?.filter { it.date in dateRange }?.mapTo(mutableSetOf()) { it.licensePeriod } ?: emptySet()
            // keep the order of the enum
            LicensePeriod.entries.filter { it in offered || it in periodsOfSales }
        } ?: LicensePeriod.entries

        for (period in periods) {
            for (type in CustomerType.entries) {
                sales[type to period] = MonetaryAmountTracker(exchangeRates)
            }
        }

        trials = data.getTrials()
            ?.filter { it.date in dateRange }
            ?.sortedBy { it.customer.country }
            ?: emptyList()
    }

    override suspend fun process(sale: PluginSale) {
        if (sale.date in dateRange) {
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
        }

        // one row per country, the customer type isn't shown
        val trialRows = trials
            .groupBy { it.customer.country }
            .map { (country, trials) ->
                SimpleDateTableRow(
                    columnTrialCountry to (country.takeIf(String::isNotEmpty) ?: NoValue),
                    columnTrialCount to trials.size.toBigInteger()
                )
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