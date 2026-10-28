/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customerType

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.data.chart.ChartMetric
import dev.ja.marketplace.data.chart.ChartMetricValues
import dev.ja.marketplace.data.chart.ChartWindow
import dev.ja.marketplace.exchangeRate.EcbExchangeRateData
import dev.ja.marketplace.exchangeRate.ExchangeRates
import dev.ja.marketplace.mcp.McpTestData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CustomerTypeShareTest {
    // sales: 2024-03-10 individual 100 USD, 2024-11-05 organization 300 USD, 2025-02-01 individual 10 USD
    private val values = ChartMetricValues.create(McpTestData.sales, emptyList(), emptyList(), emptyList(), ExchangeRates("USD", EcbExchangeRateData()), YearMonthDay(2025, 3, 31))

    private fun share(window: ChartWindow, type: CustomerType, day: YearMonthDay): BigDecimal? {
        return values.series(ChartMetric.customerTypeShare(type), window)!!.values[values.days.indexOf(day)]
    }

    @Test
    fun shareOfTheMonthBeforeEachDay() {
        // the last day of a month is the calendar month, like the table
        assertEquals(BigDecimal("100.00"), share(ChartWindow.Month, CustomerType.Individual, YearMonthDay(2024, 3, 31)))
        assertEquals(BigDecimal("0.00"), share(ChartWindow.Month, CustomerType.Individual, YearMonthDay(2024, 11, 30)))
        assertEquals(BigDecimal("100.00"), share(ChartWindow.Month, CustomerType.Organization, YearMonthDay(2024, 11, 30)))
        // from 2024-03-06 until 2024-04-05
        assertEquals(BigDecimal("100.00"), share(ChartWindow.Month, CustomerType.Individual, YearMonthDay(2024, 4, 5)))
        // no sales in the month before the day
        assertNull(share(ChartWindow.Month, CustomerType.Individual, YearMonthDay(2024, 5, 20)))
    }

    @Test
    fun shareOfTheYearBeforeEachDay() {
        assertEquals(BigDecimal("25.00"), share(ChartWindow.Year, CustomerType.Individual, YearMonthDay(2024, 12, 31)))
        assertEquals(BigDecimal("75.00"), share(ChartWindow.Year, CustomerType.Organization, YearMonthDay(2024, 12, 31)))
        // 110 of 410 USD from 2024-03-01 until 2025-02-28
        assertEquals(BigDecimal("26.83"), share(ChartWindow.Year, CustomerType.Individual, YearMonthDay(2025, 2, 28)))
    }
}
