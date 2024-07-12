/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import dev.ja.marketplace.client.YearMonthDay
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExchangeRatesTest {
    @Test
    fun historicDate() {
        val exchangeRates = ExchangeRates("USD")
        val amount = FastMoney.of(10, "EUR")
        assertEquals(FastMoney.of(10.963, "USD"), exchangeRates.convert(YearMonthDay(2020, 4, 13), amount))
        assertEquals(FastMoney.of(10.652, "USD"), exchangeRates.convert(YearMonthDay(2024, 4, 13), amount))
    }

    @Test
    fun futureDate() {
        val exchangeRates = ExchangeRates("USD")
        val amount = FastMoney.of(10, "EUR")
        val expected = exchangeRates.convert(YearMonthDay.now(), amount)

        assertEquals(expected, exchangeRates.convert(YearMonthDay.now().add(0, 0, 1), amount))
        assertEquals(expected, exchangeRates.convert(YearMonthDay.now().add(10, 10, 10), amount))
    }
}