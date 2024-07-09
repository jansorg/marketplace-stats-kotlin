/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.*
import dev.ja.marketplace.exchangeRate.ExchangeRates
import dev.ja.marketplace.services.Currency

/**
 * Tracks payments with currencies and converts them into the target currency.
 */
class AmountTargetCurrencyTracker(private val exchangeRates: ExchangeRates) {
    private var sumUSD = Amount.ZERO
    private var sumTargetCurrency = Amount.ZERO

    suspend fun add(date: YearMonthDay, amountUSD: Amount, amount: Amount, currency: Currency) {
        sumUSD += amountUSD
        sumTargetCurrency += when {
            MarketplaceCurrencies.USD.hasCode(exchangeRates.targetCurrencyCode) -> amountUSD
            else -> exchangeRates.convert(date, amount, currency.isoCode)
        }
    }

    fun getTotalAmountUSD(): Amount {
        return sumUSD
    }

    fun getTotalAmount(): AmountWithCurrency {
        return sumTargetCurrency.withCurrency(exchangeRates.targetCurrencyCode)
    }
}