/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import dev.ja.marketplace.client.Amount
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.services.Currency
import it.unimi.dsi.fastutil.objects.Object2DoubleMap
import java.math.BigDecimal
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicReference

val EmptyExchangeRates = ExchangeRates(IdentityExchangeRateProvider, YearMonthDay.now(), "USD", emptyList())

/**
 * Prefetched exchange rates.
 */
class ExchangeRates(
    private val exchangeRateProvider: ExchangeRateProvider,
    private val firstValidDate: YearMonthDay,
    val targetCurrencyCode: String,
    supportedSourceCurrencies: Iterable<Currency>,
) {
    private val sourceCurrencyCodes = supportedSourceCurrencies.map(Currency::isoCode) - targetCurrencyCode
    private val latestFetchedDate = AtomicReference(YearMonthDay.MIN)
    private val exchangeRateInverter: DoubleTransformer = { 1.0 / it }

    // key must only be the date, because the map uses compareTo for equality
    private val cachedRates = ConcurrentSkipListMap<YearMonthDay, Object2DoubleMap<String>>()
    private val latestExchangeRate = AtomicReference<ExchangeRateSet>(null)

    suspend fun convert(date: YearMonthDay, amount: Amount, sourceCurrency: String): Amount {
        return when {
            sourceCurrency == targetCurrencyCode -> amount
            else -> amount * BigDecimal.valueOf(getCurrencyConversionFactor(date, sourceCurrency))
        }
    }

    private suspend fun getCurrencyConversionFactor(date: YearMonthDay, sourceCurrency: String): Double {
        if (sourceCurrency == targetCurrencyCode) {
            return 1.0
        }

        val currentStableRateDate = YearMonthDay.now().add(0, 0, -1)

        // handling of latest, unstable rate
        if (date > currentStableRateDate) {
            var cachedLatest = latestExchangeRate.get()
            if (cachedLatest == null || cachedLatest.date != date) {
                // fetch and cache the latest exchange rate
                val latestExchangeRates = exchangeRateProvider.fetchLatestExchangeRates(
                    targetCurrencyCode,
                    sourceCurrencyCodes,
                    exchangeRateInverter
                )

                cachedLatest = latestExchangeRates.copy(date = date)
                this.latestExchangeRate.set(cachedLatest)
            }

            val value = cachedLatest.exchangeRates.getOrDefault(sourceCurrency as Any, -1.0)
            return when {
                value >= 0.0 -> value
                else -> throw IllegalStateException("Latest exchange rate unavailable for $date, $sourceCurrency")
            }
        }

        // handling of stable rates
        if (date > latestFetchedDate.get()) {
            cacheAll(firstValidDate.rangeTo(currentStableRateDate))
            latestFetchedDate.set(currentStableRateDate)
        }

        // day's rate. For missing dates (e.g. weekend) try the previous rate and then the next available date as fallback
        val bestEntry = cachedRates.floorEntry(date) ?: cachedRates.ceilingEntry(date)
        val value = bestEntry?.value?.getOrDefault(sourceCurrency as Any, -1.0)
        return when {
            value != null && value >= 0.0 -> value
            else -> throw IllegalStateException("No cached exchange rate available for $date")
        }
    }

    // Because we have multiple source currencies and only a single target currency we're requesting the reverse exchange rate.
    // The API only supports one base currency and [1,n] target currencies.
    private suspend fun cacheAll(dateRange: YearMonthDayRange) {
        val exchangeRates = exchangeRateProvider.fetchExchangeRates(
            dateRange,
            targetCurrencyCode,
            sourceCurrencyCodes,
            exchangeRateInverter
        )

        for (result in exchangeRates) {
            cachedRates[result.date] = result.exchangeRates
        }
    }
}