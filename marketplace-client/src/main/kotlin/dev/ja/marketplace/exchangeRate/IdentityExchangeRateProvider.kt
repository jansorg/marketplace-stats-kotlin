/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import it.unimi.dsi.fastutil.objects.Object2DoubleMaps

object IdentityExchangeRateProvider : ExchangeRateProvider {
    override suspend fun fetchExchangeRates(
        dates: YearMonthDayRange,
        fromIsoCode: String,
        toIsoCodes: Iterable<String>,
        exchangeRateTransformer: DoubleTransformer?
    ): Iterable<ExchangeRateSet> {
        return emptyList()
    }

    override suspend fun fetchLatestExchangeRates(
        fromIsoCode: String,
        toIsoCodes: Iterable<String>,
        exchangeRateTransformer: DoubleTransformer?
    ): ExchangeRateSet {
        return ExchangeRateSet(YearMonthDay.now(), Object2DoubleMaps.emptyMap())
    }
}