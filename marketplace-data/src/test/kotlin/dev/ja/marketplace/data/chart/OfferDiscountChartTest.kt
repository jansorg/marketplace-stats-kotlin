/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.chart

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.exchangeRate.EcbExchangeRateData
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OfferDiscountChartTest {
    private val exchangeRates = ExchangeRates("USD", EcbExchangeRateData())
    private val today = YearMonthDay(2025, 9, 30)

    private val sales = listOf(
        // the percent of the offer changed, each sale has its own percent
        sale("s1", YearMonthDay(2025, 6, 10), 80, PluginSaleItemDiscount("\"Summer, 20% @ off\" offer", 20.0)),
        sale("s2", YearMonthDay(2025, 7, 5), 50, PluginSaleItemDiscount("\"Summer, 20% @ off\" offer", 50.0)),
        sale("s3", YearMonthDay(2025, 7, 20), 60, PluginSaleItemDiscount("\"Summer, 20% @ off\" offer", 40.0), PluginSaleItemDiscount("Reseller discount", 10.0)),
        // a free license doesn't count, a discount without an offer is a metric of its description
        sale("s4", YearMonthDay(2025, 7, 21), 0, PluginSaleItemDiscount("\"Summer, 20% @ off\" offer", 100.0)),
        sale("s5", YearMonthDay(2025, 7, 22), 60, PluginSaleItemDiscount("40% continuity discount", 40.0)),
        sale("s6", YearMonthDay(2025, 8, 1), 90, PluginSaleItemDiscount("\"Other offer\" offer", 10.0)),
        // an offer without a discount amount has no metric
        sale("s7", YearMonthDay(2025, 8, 2), 0, PluginSaleItemDiscount("\"Free offer\" offer", 100.0)),
    )

    private val values = ChartMetricValues.create(sales, LicenseInfo.createFrom(sales), emptyList(), emptyList(), exchangeRates, today)

    @Test
    fun metricsOfTheOffersOfTheSales() {
        val offerMetrics = values.metrics.filter { it.discountName != null }
        // the reseller discount and the free offer have no metric
        assertEquals(listOf("40% continuity discount", "Other offer", "Summer, 20% @ off"), offerMetrics.map { it.discountName })
        val metric = ChartMetric.discount("Summer, 20% @ off")
        assertSame(metric, offerMetrics[2])
        assertEquals("Discount: Summer, 20% @ off", metric.title)
        assertEquals(ChartUnit.Amount, metric.unit)
        // the ID has no separators of the charts
        assertTrue(metric.id.matches(Regex("discount\\.[A-Za-z0-9_-]+")), metric.id)
        assertSame(metric, ChartMetric.byId(metric.id))
        assertNull(ChartMetric.byId("discount.!"))
        assertNull(ChartMetric.byId("discount."))
        assertFalse(metric in ChartMetric.all)
    }

    @Test
    fun discountAmountOfEachWindow() {
        val metric = ChartMetric.discount("Summer, 20% @ off")
        // 20 of June, 50 + 40 of July, the reseller discount and the free license have no amount
        assertEquals(BigDecimal(20), valueAt(metric, ChartWindow.CalendarMonth, YearMonthDay(2025, 6, 30)))
        assertEquals(BigDecimal(90), valueAt(metric, ChartWindow.CalendarMonth, YearMonthDay(2025, 7, 31)))
        assertEquals(BigDecimal(0), valueAt(metric, ChartWindow.CalendarMonth, YearMonthDay(2025, 8, 31)))
        assertEquals(BigDecimal(110), valueAt(metric, ChartWindow.CalendarYear, today))
        assertEquals(BigDecimal(40), valueAt(metric, ChartWindow.Day, YearMonthDay(2025, 7, 20)))

        assertEquals(BigDecimal(10), valueAt(ChartMetric.discount("Other offer"), ChartWindow.CalendarYear, today))
        assertEquals(BigDecimal(40), valueAt(ChartMetric.discount("40% continuity discount"), ChartWindow.CalendarYear, today))
        // an offer without sales has no values
        assertNull(values.series(ChartMetric.discount("Unused"), ChartWindow.Month))
    }

    private fun valueAt(metric: ChartMetric, window: ChartWindow, date: YearMonthDay): BigDecimal {
        val series = values.series(metric, window)!!
        return series.values[values.days.indexOf(date)]!!.stripTrailingZeros().let { if (it.signum() == 0) BigDecimal.ZERO else it }
            .let { BigDecimal(it.toPlainString()) }
    }

    private fun sale(ref: String, date: YearMonthDay, amountUSD: Int, vararg discounts: PluginSaleItemDiscount): PluginSale {
        val amount = FastMoney.of(amountUSD, MarketplaceCurrencies.USD)
        val lineItem = SubscriptionPluginSaleItem(
            PluginSaleItemType.New, listOf("license-$ref"), amount, amount, discounts.toList(), date.rangeTo(date.add(1, 0, -1)),
        )
        val customer = CustomerInfo(ref.hashCode(), "Germany", CustomerType.Individual)
        return PluginSale(ref, date, amount, amount, LicensePeriod.Annual, customer, null, listOf(lineItem))
    }
}
