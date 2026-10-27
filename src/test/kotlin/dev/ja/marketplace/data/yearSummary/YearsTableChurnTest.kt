/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.yearSummary

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.CustomerInfo
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginSaleItemType
import dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
import dev.ja.marketplace.data.DataTableColumn
import dev.ja.marketplace.data.IncompleteValue
import dev.ja.marketplace.data.LinkedChurnRate
import dev.ja.marketplace.data.SimpleDateTableRow
import dev.ja.marketplace.data.chart.ChartMetric
import dev.ja.marketplace.data.chart.ChartMetricValues
import dev.ja.marketplace.data.chart.ChartWindow
import dev.ja.marketplace.exchangeRate.ExchangeRates
import dev.ja.marketplace.mcp.McpTestData
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The combined churn rates of the tables of the years are the values of the chart metric with the calendar year.
 */
class YearsTableChurnTest {
    private val today = YearMonthDay.now()

    /**
     * Annual and monthly licenses, which are renewed several times, sometimes late, and then end. Some licenses switch
     * the period, some are free.
     */
    private val sales: List<PluginSale> = run {
        val random = Random(209)
        (1..90).flatMap { customerId ->
            val customer = CustomerInfo(customerId, "Germany", CustomerType.Individual, "Customer $customerId")
            var period = if (customerId % 2 == 0) LicensePeriod.Annual else LicensePeriod.Monthly
            val amount = if (customerId % 9 == 0) 0 else 10
            var start = YearMonthDay(2023, 1, 1).add(0, 0, random.nextInt(700))
            (0..random.nextInt(if (period == LicensePeriod.Annual) 3 else 14)).map { renewal ->
                if (customerId % 5 == 0 && renewal == 2) {
                    period = if (period == LicensePeriod.Annual) LicensePeriod.Monthly else LicensePeriod.Annual
                }
                val type = if (renewal == 0) PluginSaleItemType.New else PluginSaleItemType.Renew
                val sale = sale(customer, "license-$customerId", start, period, amount, type)
                start = (sale.lineItems.single() as SubscriptionPluginSaleItem).subscriptionDates.end
                    .add(0, 0, 1 + if (random.nextInt(4) == 0) random.nextInt(90) else 0)
                sale
            }
        }.filter { it.date <= today }.sortedBy { it.date }
    }

    /** @return The churned licenses and the licenses at the start of the calendar year until [day], `null` without licenses at the start. */
    private fun metric(values: ChartMetricValues, day: YearMonthDay): Pair<Int, Int>? {
        val ratio = values.series(ChartMetric.ChurnCombined, ChartWindow.CalendarYear)!!.ratio!!
        val index = values.days.indexOf(day)
        val previous = ratio.denominators[index]!!.toInt()
        return if (previous == 0) null else ratio.numerators[index]!!.toInt() to previous
    }

    @Test
    suspend fun combinedChurnOfTheYearsIsTheChurnOfTheChartMetric() {
        val data = McpTestData.pluginData(sales = sales)
        val table = YearlySummaryTable()
        table.init(data)
        data.getSales()!!.forEach { table.process(it) }
        data.getLicenses()!!.forEach { table.process(it) }
        val rendered = table.renderTable()
        val column = table.columns.single { it.id == "churn-combined-paid" }
        val yearColumn = table.columns.single { it.id == "year" }
        assertTrue(column.tooltip!!.startsWith("Combined churn rate"))

        val values = ChartMetricValues.create(sales, LicenseInfo.createFrom(sales), emptyList(), emptyList(), ExchangeRates("USD"), today)
        var yearsWithChurn = 0
        val rows = rendered.sections.single().rows.map { it as SimpleDateTableRow }
        for (row in rows) {
            val year = row.values[yearColumn] as Int
            val expected = metric(values, minOf(today, YearMonthDay(year, 12, 31)))
            assertEquals(expected, rate(row, column), "year $year")
            if (expected != null && expected.first > 0) {
                yearsWithChurn++
            }
        }
        assertTrue(rows.size >= 3, "compared years: ${rows.size}")
        assertTrue(yearsWithChurn >= 2, "years with churn: $yearsWithChurn")
    }

    @Test
    suspend fun combinedChurnOfAllPluginsPoolsTheLicensesOfThePlugins() {
        // two plugins with the same IDs of the licenses
        val otherSales = sales.filter { it.customer.code % 3 == 0 }
        val table = AllPluginsSummaryTable(ExchangeRates("USD"))
        table.add(sales, LicenseInfo.createFrom(sales), emptyList())
        table.add(otherSales, LicenseInfo.createFrom(otherSales), emptyList())
        val rendered = table.renderTable()
        val column = table.columns.single { it.id == "churn-combined-paid" }
        val yearColumn = table.columns.single { it.id == "year" }

        val single = ChartMetricValues.create(sales, LicenseInfo.createFrom(sales), emptyList(), emptyList(), ExchangeRates("USD"), today)
        val other = ChartMetricValues.create(otherSales, LicenseInfo.createFrom(otherSales), emptyList(), emptyList(), ExchangeRates("USD"), today)
        val all = ChartMetricValues.allPlugins(sales + otherSales, emptyList(), listOf(LicenseInfo.createFrom(sales), LicenseInfo.createFrom(otherSales)), ExchangeRates("USD"), today)
        var yearsWithChurn = 0
        for (row in rendered.sections.single().rows.map { it as SimpleDateTableRow }) {
            val year = row.values[yearColumn] as Int
            val day = minOf(today, YearMonthDay(year, 12, 31))
            val expected = listOfNotNull(metric(single, day), metric(other, day))
                .reduceOrNull { a, b -> a.first + b.first to a.second + b.second }
            assertEquals(expected, metric(all, day), "metric of all plugins, year $year")
            // the rate of all plugins has no link, the tooltip has the counts
            assertEquals(expected?.let { (churned, previous) -> "$churned of $previous churned" }, row.tooltips[column]?.lineSequence()?.first()?.substringBefore(" until"), "year $year")
            if (expected != null && expected.first > 0) {
                yearsWithChurn++
            }
        }
        assertTrue(yearsWithChurn >= 2, "years with churn: $yearsWithChurn")
    }

    @Test
    suspend fun combinedChurnIsEmptyWithoutLicenses() {
        val data = McpTestData.pluginData(sales = emptyList())
        val table = YearlySummaryTable()
        table.init(data)
        val rendered = table.renderTable()
        val column = table.columns.single { it.id == "churn-combined-paid" }
        rendered.sections.flatMap { it.rows }.forEach { assertNull(rate(it as SimpleDateTableRow, column)) }
    }

    /** @return The churned licenses and the licenses at the start of the period, `null` without licenses at the start. */
    private fun rate(row: SimpleDateTableRow, column: DataTableColumn): Pair<Int, Int>? {
        val value = row.values[column].let { if (it is IncompleteValue) it.value else it }
        return when (value) {
            is LinkedChurnRate -> value.churnedCount to value.previousActiveCount
            else -> null
        }
    }

    private fun sale(
        customer: CustomerInfo,
        licenseId: String,
        start: YearMonthDay,
        period: LicensePeriod,
        amountUSD: Int,
        type: PluginSaleItemType,
    ): PluginSale {
        val amount = FastMoney.of(amountUSD, MarketplaceCurrencies.USD)
        val validity = when (period) {
            LicensePeriod.Annual -> start.rangeTo(start.add(1, 0, -1))
            else -> start.rangeTo(start.add(0, 1, -1))
        }
        val ref = "$licenseId-$start"
        val lineItem = SubscriptionPluginSaleItem(type, listOf(licenseId), amount, amount, emptyList(), validity)
        return PluginSale(ref, start, amount, amount, period, customer, null, listOf(lineItem))
    }
}
