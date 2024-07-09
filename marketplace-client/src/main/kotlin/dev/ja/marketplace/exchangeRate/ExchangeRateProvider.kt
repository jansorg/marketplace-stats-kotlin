/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import it.unimi.dsi.fastutil.objects.Object2DoubleMap

/**
 * Provides current or historical currency exchange rates.
 */
interface ExchangeRateProvider {
    /**
     * @param dates `null` for the current exchange rate or a date range for historical data.
     * @param fromIsoCode ISO code of the base currency
     * @param toIsoCodes ISO codes of the target currencies
     * @return Map of target currency ISO code to the exchange rate factor
     */
    suspend fun fetchExchangeRates(
        dates: YearMonthDayRange,
        fromIsoCode: String,
        toIsoCodes: Iterable<String>,
        exchangeRateTransformer: DoubleTransformer? = null,
    ): Iterable<ExchangeRateSet>

    suspend fun fetchLatestExchangeRates(
        fromIsoCode: String,
        toIsoCodes: Iterable<String>,
        exchangeRateTransformer: DoubleTransformer? = null,
    ): ExchangeRateSet
}

typealias DoubleTransformer = (Double) -> Double

data class ExchangeRateSet(
    val date: YearMonthDay,
    // currency -> exchange rate
    val exchangeRates: Object2DoubleMap<String>,
) : Comparable<ExchangeRateSet> {
    override fun compareTo(other: ExchangeRateSet): Int {
        return date.compareTo(other.date)
    }
}