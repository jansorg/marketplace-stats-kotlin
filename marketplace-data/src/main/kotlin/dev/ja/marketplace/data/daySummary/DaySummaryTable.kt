/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.daySummary

import dev.ja.marketplace.client.*
import dev.ja.marketplace.data.*
import java.math.BigInteger

class DaySummaryTable(
    val date: YearMonthDay,
    title: String
) : SimpleDataTable(title, cssClass = "small table-striped"), MarketplaceDataSink {
    private val sales = mutableListOf<PluginSale>()
    private lateinit var trials: List<PluginTrial>

    private val columnSubscriptionType = DataTableColumn("sales-subscription", null, "col-right")
    private val columnCustomerType = DataTableColumn("sales-type", null, "col-right")
    private val columnAmount = DataTableColumn("sales-amount", "Sales", "num", preSorted = AriaSortOrder.Descending)

    private val columnTrialCountry = DataTableColumn("trial-country", null, "col-right", columnSpan = 2)
    private val columnTrialCount = DataTableColumn("trial-count", "Trials", "num", preSorted = AriaSortOrder.Descending)

    override val columns: List<DataTableColumn> = listOf(columnSubscriptionType, columnCustomerType, columnAmount)

    override suspend fun init(data: PluginData) {
        trials = data.trials
            ?.filter { it.date == date }
            ?.sortedBy { it.customer.country }
            ?: emptyList()
    }

    override fun process(sale: PluginSale) {
        if (sale.date == date) {
            sales += sale
        }
    }

    override fun process(licenseInfo: LicenseInfo) {
        // ignored
    }

    override fun createSections(): List<DataTableSection> {
        val salesTable = sales
            .groupBy { it.customer.type }
            .mapValues { it.value.groupBy { sale -> sale.licensePeriod } }
            .flatMap { (type, licensePeriodWithSales) ->
                licensePeriodWithSales.map { (licensePeriod, sales) ->
                    SimpleDateTableRow(
                        columnCustomerType to type,
                        columnSubscriptionType to licensePeriod,
                        columnAmount to sales.sumOf { it.amountUSD }.withCurrency(Currency.USD),
                    )
                }
            }.sortedByDescending { it.values[columnAmount] as? AmountWithCurrency }

        val trialRows = trials
            .groupBy { it.customer.type }
            .mapValues { it.value.groupBy { it.customer.country } }
            .flatMap { (_, countryTrials) ->
                countryTrials.map { (country, trials) ->
                    SimpleDateTableRow(
                        columnTrialCountry to (country.takeIf(Country::isNotEmpty) ?: NoValue),
                        columnTrialCount to trials.size.toBigInteger()
                    )
                }
            }
            .sortedByDescending { it.values[columnTrialCount] as BigInteger }

        return listOf(
            SimpleTableSection(
                rows = salesTable,
                columns = listOf(columnSubscriptionType, columnCustomerType, columnAmount),
                footer = SimpleRowGroup(SimpleDateTableRow(columnAmount to sales.sumOf { it.amountUSD }.withCurrency(Currency.USD)))
            ),
            SimpleTableSection(
                title = "",
                rows = trialRows,
                columns = listOf(columnTrialCountry, columnTrialCount),
                footer = SimpleRowGroup(SimpleDateTableRow(columnTrialCount to trials.size))
            )
        )
    }
}