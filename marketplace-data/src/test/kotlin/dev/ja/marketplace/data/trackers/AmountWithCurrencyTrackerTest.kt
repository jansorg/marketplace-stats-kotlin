/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.Amount
import dev.ja.marketplace.client.AmountWithCurrency
import dev.ja.marketplace.client.MarketplaceCurrencies
import dev.ja.marketplace.exchangeRate.EmptyExchangeRates
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class AmountWithCurrencyTrackerTest {
    @Test
    fun tracking() {
        val amounts = AmountWithCurrencyTracker(EmptyExchangeRates)
        amounts.add(Amount(10), MarketplaceCurrencies.EUR.isoCode)
        amounts.add(Amount(100), MarketplaceCurrencies.USD.isoCode)
        amounts.add(Amount(1000), MarketplaceCurrencies.JPY.isoCode)

        Assertions.assertEquals(
            setOf(
                AmountWithCurrency(Amount(10), MarketplaceCurrencies.EUR),
                AmountWithCurrency(Amount(100), MarketplaceCurrencies.USD),
                AmountWithCurrency(Amount(1000), MarketplaceCurrencies.JPY),
            ), amounts.getValues().toSet()
        )
    }
}