/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import dev.ja.marketplace.client.Marketplace
import it.unimi.dsi.fastutil.objects.Object2IntMap
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import java.io.InputStream
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipInputStream

/**
 * Daily euro foreign exchange reference rates published by the European Central Bank.
 * The rates are stored in primitive arrays, indexed by currency and by day.
 */
class EcbRateTable internal constructor(
    private val epochDays: IntArray,
    private val currencyIndex: Object2IntMap<String>,
    private val rates: Array<DoubleArray>,
) {
    val firstDate: LocalDate? get() = epochDays.firstOrNull()?.let { LocalDate.ofEpochDay(it.toLong()) }

    val lastDate: LocalDate? get() = epochDays.lastOrNull()?.let { LocalDate.ofEpochDay(it.toLong()) }

    /**
     * @return The date closest to [date] with published rates, or `null` if the table is empty.
     *  If two dates are equally close, the earlier date is returned.
     */
    fun closestDate(date: LocalDate): LocalDate? {
        if (epochDays.isEmpty()) {
            return null
        }

        val epochDay = date.toEpochDay().toInt()
        val index = epochDays.binarySearch(epochDay)
        if (index >= 0) {
            return date
        }

        val insertionPoint = -index - 1
        val before = epochDays.getOrNull(insertionPoint - 1)
        val after = epochDays.getOrNull(insertionPoint)
        val closest = when {
            before == null -> after!!
            after == null -> before
            after - epochDay < epochDay - before -> after
            else -> before
        }
        return LocalDate.ofEpochDay(closest.toLong())
    }

    /**
     * @return The amount of [currencyCode] for 1 EUR at the given date,
     *  or `null` if no rate was published for the date or the currency.
     */
    fun euroRate(currencyCode: String, date: LocalDate): BigDecimal? {
        if (currencyCode == "EUR") {
            return BigDecimal.ONE
        }

        val dayIndex = epochDays.binarySearch(date.toEpochDay().toInt())
        val currency = currencyIndex.getInt(currencyCode)
        if (dayIndex < 0 || currency < 0) {
            return null
        }

        val rate = rates[currency][dayIndex]
        return when {
            rate.isNaN() -> null
            // The shortest decimal representation of the double is the value published by the ECB
            else -> BigDecimal.valueOf(rate)
        }
    }

    companion object {
        /**
         * Parses the CSV file published by the ECB. Rows with a date before [minDate] are ignored.
         */
        fun parseCsv(input: InputStream, minDate: LocalDate = LocalDate.MIN): EcbRateTable {
            val lines = input.bufferedReader(Charsets.UTF_8).readLines().filter(String::isNotBlank)
            require(lines.isNotEmpty()) { "Empty ECB exchange rate data" }

            val header = lines.first().split(',').map(String::trim)
            require(header.firstOrNull() == "Date") { "Unexpected header of ECB exchange rate data: ${lines.first()}" }

            val currencies = header.drop(1).filter(String::isNotEmpty)
            val rows = lines.drop(1)
                .map { it.split(',') }
                .map { LocalDate.parse(it[0].trim()) to it }
                .filter { (date, _) -> date >= minDate }
                .sortedBy { it.first }

            val epochDays = IntArray(rows.size) { rows[it].first.toEpochDay().toInt() }
            val rates = Array(currencies.size) { currency ->
                DoubleArray(rows.size) { row ->
                    rows[row].second.getOrNull(currency + 1)?.trim()?.toDoubleOrNull() ?: Double.NaN
                }
            }
            val currencyIndex = Object2IntOpenHashMap<String>(currencies.size).apply { defaultReturnValue(-1) }
            currencies.forEachIndexed { index, currency -> currencyIndex.put(currency, index) }

            return EcbRateTable(epochDays, currencyIndex, rates)
        }
    }
}

/**
 * Lazily loads and caches the ECB exchange rates since the launch of the JetBrains Marketplace.
 * The data is shared by all [ExchangeRates] instances.
 */
class EcbExchangeRateData(
    private val dataUrl: URI = URI.create("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist.zip"),
    private val maxAge: Duration = Duration.ofHours(6),
) {
    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    @Volatile
    private var cached: Pair<Instant, EcbRateTable>? = null

    fun get(): EcbRateTable {
        val current = cached
        if (current != null && current.first.plus(maxAge) > Instant.now()) {
            return current.second
        }

        synchronized(this) {
            val latest = cached
            if (latest != null && latest.first.plus(maxAge) > Instant.now()) {
                return latest.second
            }

            val table = download()
            cached = Instant.now() to table
            return table
        }
    }

    fun invalidate() {
        cached = null
    }

    private fun download(): EcbRateTable {
        val request = HttpRequest.newBuilder(dataUrl)
            .timeout(Duration.ofMinutes(1))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            throw IllegalStateException("Unable to load ECB exchange rates from $dataUrl: HTTP ${response.statusCode()}")
        }

        ZipInputStream(response.body()).use { zip ->
            zip.nextEntry ?: throw IllegalStateException("ECB exchange rate archive $dataUrl is empty")
            return EcbRateTable.parseCsv(zip, minDate)
        }
    }

    companion object {
        // a few days before the launch to support the lookup of the rates of previous days
        private val minDate: LocalDate = Marketplace.Birthday.add(0, 0, -7).toLocalDate()

        val Default = EcbExchangeRateData()
    }
}
