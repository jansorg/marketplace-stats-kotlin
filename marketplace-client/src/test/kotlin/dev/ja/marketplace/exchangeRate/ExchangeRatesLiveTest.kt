/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import dev.ja.marketplace.client.YearMonthDay
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

/**
 * The rates of the ECB, downloaded from its server. Only run by `./gradlew liveTest`.
 */
@Tag("live")
class ExchangeRatesLiveTest {
    private val rateData = EcbExchangeRateData()
    private val exchangeRates = ExchangeRates("USD", rateData)
    private val amount = FastMoney.of(10, "EUR")

    @Test
    fun historicRates() {
        assertEquals(FastMoney.of(10.963, "USD"), exchangeRates.convert(YearMonthDay(2020, 4, 13), amount))
        assertEquals(FastMoney.of(10.652, "USD"), exchangeRates.convert(YearMonthDay(2024, 4, 13), amount))
    }

    @Test
    fun recentRates() {
        // the ECB publishes the rates of each working day
        val lastDate = rateData.get().lastDate
        assertTrue(lastDate != null && lastDate >= LocalDate.now().minusDays(10), "latest rates of $lastDate")

        val expected = exchangeRates.convert(YearMonthDay.now(), amount)
        assertEquals(expected, exchangeRates.convert(YearMonthDay.now().add(0, 0, 1), amount))
    }

    @Test
    fun unchangedRatesAreNotDownloadedAgain() {
        // outdated at once: the second get() validates the rates with If-Modified-Since, the ECB answers with 304
        val data = EcbExchangeRateData(maxAge = Duration.ZERO)
        data.use {
            val table = data.get()
            data.get()
            data.awaitBackgroundRefresh()
            // a downloaded archive would be merged into a new object
            assertSame(table, data.get())
        }
    }
}
