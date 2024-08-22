/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.currency

import javax.money.CurrencyUnit
import javax.money.Monetary

object MarketplaceCurrencies : Iterable<CurrencyUnit> {
    val USD = Monetary.getCurrency("USD")
    private val EUR = Monetary.getCurrency("EUR")
    private val JPY = Monetary.getCurrency("JPY")
    private val GBP = Monetary.getCurrency("GBP")
    private val CZK = Monetary.getCurrency("CZK")
    private val CNY = Monetary.getCurrency("CNY")

    private val allCurrencies = listOf(USD, EUR, JPY, GBP, CZK, CNY)

    override fun iterator(): Iterator<CurrencyUnit> {
        return allCurrencies.iterator()
    }

    fun of(id: String): CurrencyUnit {
        return when (id) {
            "USD" -> USD
            "EUR" -> EUR
            "JPY" -> JPY
            "GBP" -> GBP
            "CZK" -> CZK
            "CNY" -> CNY
            else -> throw IllegalStateException("Unknown currency $id")
        }
    }
}