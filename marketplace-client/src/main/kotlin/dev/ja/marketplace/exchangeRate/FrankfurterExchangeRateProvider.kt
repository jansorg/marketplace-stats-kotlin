/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import dev.ja.marketplace.client.ClientLogLevel
import dev.ja.marketplace.client.KtorHttpClientFactory
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import it.unimi.dsi.fastutil.objects.Object2DoubleArrayMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Implementation using open-source project https://github.com/hakanensari/frankfurter.
 */
class FrankfurterExchangeRateProvider(
    apiHost: String,
    apiURLProtocol: URLProtocol = URLProtocol.HTTPS,
    apiPort: Int = apiURLProtocol.defaultPort,
    logLevel: ClientLogLevel
) : ExchangeRateProvider {
    private val httpClient = KtorHttpClientFactory.createHttpClient(
        apiHost = apiHost,
        apiProtocol = apiURLProtocol,
        apiPort = apiPort,
        logLevel = logLevel
    )

    override suspend fun fetchExchangeRates(
        dates: YearMonthDayRange,
        fromIsoCode: String,
        toIsoCodes: Iterable<String>,
        exchangeRateTransformer: DoubleTransformer?,
    ): Iterable<ExchangeRateSet> {
        return requestDateRangeRates(dates, fromIsoCode, toIsoCodes, exchangeRateTransformer)
    }

    override suspend fun fetchLatestExchangeRates(
        fromIsoCode: String,
        toIsoCodes: Iterable<String>,
        exchangeRateTransformer: DoubleTransformer?,
    ): ExchangeRateSet {
        return fetchSingleDay(null, fromIsoCode, toIsoCodes, exchangeRateTransformer)
    }

    private suspend fun requestDateRangeRates(
        dates: YearMonthDayRange,
        fromIsoCode: String,
        toIsoCodes: Iterable<String>,
        exchangeRateTransformer: DoubleTransformer?,
    ): Iterable<ExchangeRateSet> {
        // The API returns daily rates for ranges up to 90 days.
        // For larger ranges, we're request for chunks of 90 days.
        val allResults = sortedMapOf<YearMonthDay, ExchangeRateSet>()

        dates.days().chunked(90).forEach { days ->
            val first = days.first()
            val last = days.last()
            when {
                first == last -> fetchSingleDay(days.first(), fromIsoCode, toIsoCodes, allResults, exchangeRateTransformer)
                else -> fetchDateRange(
                    "${first.asIsoString}..${last.asIsoString}",
                    fromIsoCode,
                    toIsoCodes,
                    allResults,
                    exchangeRateTransformer
                )
            }
        }

        return allResults.values.toList()
    }

    private suspend fun fetchDateRange(
        path: String,
        fromIsoCode: String,
        toIsoCodes: Iterable<String>,
        allResults: MutableMap<YearMonthDay, ExchangeRateSet>,
        exchangeRateTransformer: DoubleTransformer?
    ) {
        val response = httpClient.get(path) {
            parameter("from", fromIsoCode)
            parameter("to", toIsoCodes.joinToString(","))
        }.body<ExchangeDateRangeRateResponse>()

        // dates to currency/rate pairs
        response.dailyRates.forEach { (dateString, currentRateMap) ->
            val date = YearMonthDay.parse(dateString)
            val transformedRates = when (exchangeRateTransformer) {
                null -> currentRateMap
                else -> currentRateMap.mapValues { exchangeRateTransformer(it.value) }
            }
            allResults[date] = ExchangeRateSet(date, Object2DoubleArrayMap(transformedRates))
        }
    }

    private suspend fun fetchSingleDay(
        date: YearMonthDay,
        fromIsoCode: String,
        toIsoCodes: Iterable<String>,
        target: MutableMap<YearMonthDay, ExchangeRateSet>,
        exchangeRateTransformer: DoubleTransformer?
    ) {
        val result = fetchSingleDay(date, fromIsoCode, toIsoCodes, exchangeRateTransformer)
        target[result.date] = result
    }

    private suspend fun fetchSingleDay(
        date: YearMonthDay?,
        fromIsoCode: String,
        toIsoCodes: Iterable<String>,
        exchangeRateTransformer: DoubleTransformer?,
    ): ExchangeRateSet {
        val response = httpClient.get(if (date == null) "/latest" else "/${date.asIsoString}") {
            parameter("from", fromIsoCode)
            parameter("to", toIsoCodes.joinToString(","))
        }.body<ExchangeDateResponse>()

        val transformedRates = when (exchangeRateTransformer) {
            null -> response.rates
            else -> response.rates.mapValues { exchangeRateTransformer(it.value) }
        }

        return ExchangeRateSet(YearMonthDay.parse(response.date), Object2DoubleArrayMap(transformedRates))
    }
}

@Serializable
private data class ExchangeDateResponse(
    @SerialName("amount")
    val amount: Double,
    @SerialName("base")
    val base: String,
    @SerialName("date")
    val date: String,
    @SerialName("rates")
    val rates: Map<String, Double>
)

@Serializable
private data class ExchangeDateRangeRateResponse(
    @SerialName("amount")
    val amount: Double,
    @SerialName("base")
    val base: String,
    @SerialName("start_date")
    val startDate: String,
    @SerialName("end_date")
    val endDate: String,
    @SerialName("rates")
    val dailyRates: Map<String, Map<String, Double>>
)
