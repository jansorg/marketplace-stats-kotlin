/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

@file:OptIn(ExperimentalSerializationApi::class)

package dev.ja.marketplace.exchangeRate

import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.persistentCache.PersistentCacheStore
import it.unimi.dsi.fastutil.objects.Object2IntMap
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * The codes of the currencies with rates, without EUR. Discontinued currencies have no rates for recent dates.
     */
    val currencies: List<String>
        get() = currencyIndex.object2IntEntrySet().sortedBy { it.intValue }.map { it.key }

    /**
     * Merges the rates of a newer table into this table. The rates of [newer] replace the rates of the same days,
     * all other rates of this table are kept. Days and currencies, which are only available in one of the tables, are added.
     */
    fun mergedWith(newer: EcbRateTable): EcbRateTable {
        val mergedDays = (epochDays + newer.epochDays).distinct().sorted().toIntArray()
        val mergedCurrencies = (currencies + newer.currencies).distinct()

        val mergedRates = Array(mergedCurrencies.size) { index ->
            val currency = mergedCurrencies[index]
            val oldRates = currencyIndex.getInt(currency).takeIf { it >= 0 }?.let { rates[it] }
            val newRates = newer.currencyIndex.getInt(currency).takeIf { it >= 0 }?.let { newer.rates[it] }
            DoubleArray(mergedDays.size) { dayIndex ->
                val day = mergedDays[dayIndex]
                val newDayIndex = newer.epochDays.binarySearch(day)
                val oldDayIndex = epochDays.binarySearch(day)
                when {
                    newDayIndex >= 0 && newRates != null -> newRates[newDayIndex]
                    oldDayIndex >= 0 && oldRates != null -> oldRates[oldDayIndex]
                    else -> Double.NaN
                }
            }
        }

        return create(mergedDays, mergedCurrencies, mergedRates)
    }

    internal fun toCached(fetchedMillis: Long): CachedEcbRates {
        return CachedEcbRates(fetchedMillis, epochDays, currencies, rates.map(::CachedEcbCurrencyRates))
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

            return create(epochDays, currencies, rates)
        }

        internal fun fromCached(cached: CachedEcbRates): EcbRateTable {
            val days = cached.epochDays
            require(cached.currencies.size == cached.rates.size) { "Unexpected count of currencies" }
            require(days.indices.all { it == 0 || days[it - 1] < days[it] }) { "Days are not sorted" }
            require(cached.rates.all { it.rates.size == days.size }) { "Unexpected count of rates" }
            return create(days, cached.currencies, Array(cached.rates.size) { cached.rates[it].rates })
        }

        private fun create(epochDays: IntArray, currencies: List<String>, rates: Array<DoubleArray>): EcbRateTable {
            val currencyIndex = Object2IntOpenHashMap<String>(currencies.size).apply { defaultReturnValue(-1) }
            currencies.forEachIndexed { index, currency -> currencyIndex.put(currency, index) }
            return EcbRateTable(epochDays, currencyIndex, rates)
        }
    }
}

/**
 * Stored ECB exchange rates, the rates of each currency are stored in a separate list.
 */
@Serializable
internal class CachedEcbRates(
    @ProtoNumber(1) val fetchedMillis: Long,
    @ProtoNumber(2) @ProtoPacked val epochDays: IntArray,
    @ProtoNumber(3) val currencies: List<String>,
    @ProtoNumber(4) val rates: List<CachedEcbCurrencyRates>,
)

@Serializable
internal class CachedEcbCurrencyRates(
    @ProtoNumber(1) @ProtoPacked val rates: DoubleArray,
)

/**
 * Lazily loads and caches the ECB exchange rates since the launch of the JetBrains Marketplace.
 * The data is shared by all [ExchangeRates] instances.
 *
 * If the persistent cache is enabled, the rates are also stored on disk. After a restart, the stored rates are used.
 * If they're outdated, they're updated in the background and the downloaded rates are merged into the stored rates.
 */
class EcbExchangeRateData(
    private val dataUrl: URI = URI.create("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist.zip"),
    private val maxAge: Duration = Duration.ofHours(6),
    private val retryDelay: Duration = Duration.ofMinutes(10),
    private val clock: Clock = Clock.systemUTC(),
    private val downloader: (() -> EcbRateTable)? = null,
) {
    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    @Volatile
    private var cached: Pair<Instant, EcbRateTable>? = null

    @Volatile
    private var store: PersistentCacheStore? = null

    // guarded by this
    private var isDiskChecked = false

    private val isRefreshing = AtomicBoolean(false)

    @Volatile
    private var lastRefreshStart: Instant? = null

    /**
     * Enables the persistent cache of the rates in the given store.
     */
    fun enablePersistentCache(store: PersistentCacheStore) {
        synchronized(this) {
            this.store = store
            isDiskChecked = false
        }
    }

    fun get(): EcbRateTable {
        val current = cached
        if (current != null && isFresh(current.first)) {
            return current.second
        }

        synchronized(this) {
            var latest = cached
            if (latest == null && !isDiskChecked) {
                isDiskChecked = true
                latest = readStored()
                cached = latest
            }

            if (latest != null && isFresh(latest.first)) {
                return latest.second
            }

            // outdated rates are good enough until the update in the background is done, the reports don't wait for
            // the download and don't fail, if the ECB can't be reached
            if (latest != null) {
                refreshInBackground()
                return latest.second
            }

            val table = download()
            val now = clock.instant()
            cached = now to table
            writeStored(now, table)
            return table
        }
    }

    /**
     * Marks the rates as outdated. The next call of [get] returns them and updates them in the background.
     */
    fun invalidate() {
        synchronized(this) {
            val current = cached ?: readStored().also { isDiskChecked = true }
            cached = current?.let { Instant.EPOCH to it.second }
            lastRefreshStart = null
        }
    }

    /**
     * Waits until the current background update is done, for tests.
     */
    internal fun awaitBackgroundRefresh() {
        while (isRefreshing.get()) {
            Thread.sleep(5)
        }
    }

    private fun isFresh(fetched: Instant): Boolean = fetched.plus(maxAge) > clock.instant()

    // must be called with the lock held
    private fun refreshInBackground() {
        val lastStart = lastRefreshStart
        if (lastStart != null && lastStart.plus(retryDelay) > clock.instant() || !isRefreshing.compareAndSet(false, true)) {
            return
        }

        lastRefreshStart = clock.instant()
        Thread.ofVirtual().name("ecb-exchange-rates-update").start {
            try {
                val table = download()
                synchronized(this) {
                    val now = clock.instant()
                    val merged = cached?.second?.mergedWith(table) ?: table
                    cached = now to merged
                    writeStored(now, merged)
                }
            } catch (e: Exception) {
                logger.warn("Unable to update the ECB exchange rates, the cached rates are used: $e")
            } finally {
                isRefreshing.set(false)
            }
        }
    }

    private fun readStored(): Pair<Instant, EcbRateTable>? {
        val store = store ?: return null
        val stored = store.read(FILE_NAME, DATA_VERSION, CachedEcbRates.serializer()) ?: return null
        return try {
            Instant.ofEpochMilli(stored.fetchedMillis) to EcbRateTable.fromCached(stored)
        } catch (e: Exception) {
            logger.warn("Dropping invalid cached ECB exchange rates: $e")
            store.delete(FILE_NAME)
            null
        }
    }

    private fun writeStored(fetched: Instant, table: EcbRateTable) {
        store?.write(FILE_NAME, DATA_VERSION, CachedEcbRates.serializer(), table.toCached(fetched.toEpochMilli()))
    }

    private fun download(): EcbRateTable {
        downloader?.let { return it() }

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
        private val logger = LoggerFactory.getLogger(EcbExchangeRateData::class.java)

        private const val FILE_NAME = "ecb-exchange-rates"

        // version of the stored data, increment it after incompatible changes of CachedEcbRates
        private const val DATA_VERSION = 1

        // a few days before the launch to support the lookup of the rates of previous days
        private val minDate: LocalDate = Marketplace.Birthday.add(0, 0, -7).toLocalDate()

        val Default = EcbExchangeRateData()
    }
}
