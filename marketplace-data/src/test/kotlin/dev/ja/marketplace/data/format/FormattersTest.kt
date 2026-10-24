/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.format

import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import org.javamoney.moneta.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
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
        assertEquals("USD 1'234.57", format.format(Money.of(1234.567890, MarketplaceCurrencies.USD)))
    }

    @Test
    fun englishLocaleSmallAmounts() {
        val format = Formatters.createMoneyFormatter(Locale.ENGLISH)
        assertEquals("USD 0.50", format.format(Money.of(0.5, MarketplaceCurrencies.USD)))
        assertEquals("USD 0.00", format.format(Money.of(0, MarketplaceCurrencies.USD)))
        assertEquals("USD -0.50", format.format(Money.of(-0.5, MarketplaceCurrencies.USD)))
    }

    /** The cached number formats must have the same output as String.format, which they replace. */
    @Test
    fun numberFormatsLikeStringFormat() {
        val doubles = listOf(0.0, 1.005, 2.675, -1234567.125, 0.1f.toDouble(), 1e15, 12.345, -0.004, 999.995, 123456789.987)
        for (locale in listOf(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH, Locale.of("de", "ch"), Locale.of("sv"), Locale.of("hi", "IN"), Locale.of("ar", "EG"))) {
            val decimals = Formatters.createNumberFormat(locale, 2)
            val integers = Formatters.createNumberFormat(locale, 0)
            for (value in doubles) {
                assertEquals(String.format(locale, "%,.2f", value), decimals.format(BigDecimal.valueOf(value)), "$locale $value")
                val decimal = BigDecimal(value)
                assertEquals(String.format(locale, "%,.2f", decimal), decimals.format(decimal), "$locale $decimal")
                val integer = BigInteger.valueOf(value.toLong())
                assertEquals(String.format(locale, "%,d", integer), integers.format(integer), "$locale $integer")
            }
        }
    }

    @Test
    fun specialDoubles() {
        for (value in listOf(-0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1234.5)) {
            assertEquals(String.format("%,.2f", value), Formatters.formatDecimal(value), "$value")
        }
        assertEquals(String.format("%,.2f\u2009%%", BigDecimal("12.345")), Formatters.formatPercentage(BigDecimal("12.345")))
    }
}
