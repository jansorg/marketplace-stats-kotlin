/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import dev.ja.marketplace.client.YearMonthDay
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The conversions with the made-up rates of [EcbTestData], see [ExchangeRatesLiveTest] for the rates of the ECB.
 */
class ExchangeRatesTest {
    private val exchangeRates = ExchangeRates("USD", EcbTestData.rateData())
    private val amount = FastMoney.of(10, "EUR")

    @Test
    fun historicDate() {
        // the rates of the closest date: the day after a holiday, the day before a weekend
        assertEquals(FastMoney.of(12, "USD"), exchangeRates.convert(YearMonthDay(2020, 4, 13), amount))
        assertEquals(FastMoney.of(11, "USD"), exchangeRates.convert(YearMonthDay(2024, 4, 13), amount))
    }

    @Test
    fun futureDate() {
        // the latest rates
        assertEquals(FastMoney.of(11, "USD"), exchangeRates.convert(YearMonthDay.now(), amount))
        assertEquals(FastMoney.of(11, "USD"), exchangeRates.convert(YearMonthDay.now().add(0, 0, 1), amount))
        assertEquals(FastMoney.of(11, "USD"), exchangeRates.convert(YearMonthDay.now().add(10, 10, 10), amount))
    }
}
