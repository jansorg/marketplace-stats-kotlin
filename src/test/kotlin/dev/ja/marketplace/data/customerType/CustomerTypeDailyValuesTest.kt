/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.customerType

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.CustomerType
import dev.ja.marketplace.exchangeRate.ExchangeRates
import dev.ja.marketplace.mcp.McpTestData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CustomerTypeDailyValuesTest {
    // sales: 2024-03-10 individual 100 USD, 2024-11-05 organization 300 USD, 2025-02-01 individual 10 USD
    private val days = (0..395).map { YearMonthDay(2024, 3, 1).add(0, 0, it) }
    private val values = CustomerTypeDailyValues.create(McpTestData.sales, days, ExchangeRates("USD"))

    private fun share(period: String, type: CustomerType, day: YearMonthDay): BigDecimal? {
        return values.getValue(CustomerTypeDailyValues.seriesId(period, type)).values[days.indexOf(day)]
    }

    @Test
    fun shareOfTheMonthBeforeEachDay() {
        // the last day of a month is the calendar month, like the table
        assertEquals(BigDecimal("100.00"), share("month", CustomerType.Individual, YearMonthDay(2024, 3, 31)))
        assertEquals(BigDecimal("0.00"), share("month", CustomerType.Individual, YearMonthDay(2024, 11, 30)))
        assertEquals(BigDecimal("100.00"), share("month", CustomerType.Organization, YearMonthDay(2024, 11, 30)))
        // from 2024-03-06 until 2024-04-05
        assertEquals(BigDecimal("100.00"), share("month", CustomerType.Individual, YearMonthDay(2024, 4, 5)))
        // no sales in the month before the day
        assertNull(share("month", CustomerType.Individual, YearMonthDay(2024, 5, 20)))
    }

    @Test
    fun shareOfTheYearBeforeEachDay() {
        assertEquals(BigDecimal("25.00"), share("year", CustomerType.Individual, YearMonthDay(2024, 12, 31)))
        assertEquals(BigDecimal("75.00"), share("year", CustomerType.Organization, YearMonthDay(2024, 12, 31)))
        // 110 of 410 USD from 2024-03-01 until 2025-02-28
        assertEquals(BigDecimal("26.83"), share("year", CustomerType.Individual, YearMonthDay(2025, 2, 28)))
    }
}
