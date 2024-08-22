/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.format

import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import org.javamoney.moneta.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.*

class FormattersTest {
    @Test
    fun englishLocale() {
        val format = Formatters.createMoneyFormatter(Locale.ENGLISH)
        assertEquals("USD 1,234.57", format.format(Money.of(1234.567890, MarketplaceCurrencies.USD)))
    }

    @Test
    fun germanLocale() {
        val format = Formatters.createMoneyFormatter(Locale.GERMAN)
        assertEquals("1.234,57 USD", format.format(Money.of(1234.567890, MarketplaceCurrencies.USD)))
    }

    @Test
    fun swissLocale() {
        val format = Formatters.createMoneyFormatter(Locale.of("de", "ch"))
        assertEquals("USD 1’234.57", format.format(Money.of(1234.567890, MarketplaceCurrencies.USD)))
    }
}