/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.plus
import dev.ja.marketplace.exchangeRate.ExchangeRates
import org.javamoney.moneta.FastMoney
import javax.money.MonetaryAmount

/**
 * Tracks payments with currencies and converts them into the target currency.
 */
class MonetaryAmountTracker(private val exchangeRates: ExchangeRates) {
    private var sumUSD: MonetaryAmount = FastMoney.zero(MarketplaceCurrencies.USD)
    private var sumTargetCurrency: MonetaryAmount = FastMoney.zero(exchangeRates.targetCurrency)

    fun add(date: YearMonthDay, amountUSD: MonetaryAmount, amount: MonetaryAmount) {
        sumUSD += amountUSD
        sumTargetCurrency += when (exchangeRates.targetCurrency) {
            MarketplaceCurrencies.USD -> amountUSD
            amount.currency -> amount
            else -> exchangeRates.convert(date, amount)
        }
    }

    fun getTotalAmountUSD(): MonetaryAmount {
        return sumUSD
    }

    fun getTotalAmount(): MonetaryAmount {
        return sumTargetCurrency
    }
}