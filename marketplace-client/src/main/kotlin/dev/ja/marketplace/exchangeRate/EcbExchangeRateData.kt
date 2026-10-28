/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

@file:OptIn(ExperimentalSerializationApi::class)

package dev.ja.marketplace.exchangeRate

import dev.ja.marketplace.client.HttpClientBuilder
import dev.ja.marketplace.client.HttpClientFactory
import dev.ja.marketplace.client.Marketplace
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
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

    internal fun toCached(fetchedMillis: Long, lastModified: String? = null): CachedEcbRates {
        return CachedEcbRates(fetchedMillis, epochDays, currencies, rates.map(::CachedEcbCurrencyRates), lastModified)
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
    /** The `Last-Modified` of the archive of the ECB, `null` if it's unknown, e.g. of files of older versions. */
    @ProtoNumber(5) val lastModified: String? = null,
)

@Serializable
internal class CachedEcbCurrencyRates(
    @ProtoNumber(1) @ProtoPacked val rates: DoubleArray,
)

/**
 * Lazily loads and caches the ECB exchange rates since the launch of the JetBrains Marketplace.
 * An application creates one instance, which its [ExchangeRates] share. [close] closes the HTTP client of the downloads.
 *
 * If the persistent cache is enabled, the rates are also stored on disk. After a restart, the stored rates are used.
 * If they're outdated, they're updated in the background and the downloaded rates are merged into the stored rates.
 * An update sends the `Last-Modified` of the previous download as `If-Modified-Since`, the ECB answers unchanged rates
 * with 304 without the archive.
 */
class EcbExchangeRateData(
    private val dataUrl: URI = URI.create(DEFAULT_DATA_URL),
    private val maxAge: Duration = Duration.ofHours(6),
    private val retryDelay: Duration = Duration.ofMinutes(10),
    private val clock: Clock = Clock.systemUTC(),
    private val downloader: (() -> EcbRateTable)? = null,
    private val httpClientBuilder: HttpClientBuilder = HttpClientFactory.builder(),
) : AutoCloseable {
    // created on the first download. Without retries: get() blocks the callers while it downloads the rates, the
    // update in the background is tried again after the retryDelay.
    private val httpClientDelegate = lazy { httpClientBuilder.withRequestRetry(false).build() }
    private val httpClient: HttpClient by httpClientDelegate

    @Volatile
    private var isClosed = false

    @Volatile
    private var cached: Pair<Instant, EcbRateTable>? = null

    // the Last-Modified of the archive of the cached rates, guarded by this
    private var lastModified: String? = null

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

    /** Closes the HTTP client of the downloads, downloads fail afterward. */
    override fun close() {
        isClosed = true
        if (httpClientDelegate.isInitialized()) {
            httpClient.close()
        }
    }

    /**
     * If the HTTP client of the downloads was created and isn't closed, for tests. The job of a closed client is still
     * active while its coroutines finish, it can't tell if the client was closed.
     */
    internal val isHttpClientOpen: Boolean
        get() = httpClientDelegate.isInitialized() && !isClosed

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

            // without cached rates, the archive is always downloaded
            val download = download(ifModifiedSince = null) ?: throw IllegalStateException("No ECB exchange rates of $dataUrl")
            val now = clock.instant()
            cached = now to download.table
            lastModified = download.lastModified
            writeStored(now, download.table)
            return download.table
        }
    }

    /**
     * @return The loaded rates, without loading them, `null` if they weren't loaded yet. A new object after an update.
     */
    fun current(): EcbRateTable? = cached?.second

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
        val ifModifiedSince = lastModified
        Thread.ofVirtual().name("ecb-exchange-rates-update").start {
            try {
                val download = download(ifModifiedSince)
                synchronized(this) {
                    val now = clock.instant()
                    // unchanged rates (304) keep the cached object, they're only fresh again
                    val current = cached?.second
                    val table = when {
                        download == null -> current ?: return@synchronized
                        current == null -> download.table
                        else -> current.mergedWith(download.table)
                    }
                    if (download != null) {
                        lastModified = download.lastModified
                    }
                    cached = now to table
                    writeStored(now, table)
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
            val result = Instant.ofEpochMilli(stored.fetchedMillis) to EcbRateTable.fromCached(stored)
            lastModified = stored.lastModified
            result
        } catch (e: Exception) {
            logger.warn("Dropping invalid cached ECB exchange rates: $e")
            store.delete(FILE_NAME)
            null
        }
    }

    // must be called with the lock held
    private fun writeStored(fetched: Instant, table: EcbRateTable) {
        store?.write(FILE_NAME, DATA_VERSION, CachedEcbRates.serializer(), table.toCached(fetched.toEpochMilli(), lastModified))
    }

    private class Download(val table: EcbRateTable, val lastModified: String?)

    /**
     * @param ifModifiedSince The `Last-Modified` of the cached rates, `null` to download the archive in any case.
     * @return The downloaded rates, `null` if they're unchanged since [ifModifiedSince].
     */
    private fun download(ifModifiedSince: String?): Download? {
        downloader?.let { return Download(it(), null) }

        // the callers of get() don't suspend, the download blocks the calling thread like before
        val response = runBlocking {
            try {
                val response = httpClient.get(dataUrl.toString()) {
                    timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT.toMillis() }
                    // the ECB compares the date with the date of the archive, the value is sent unchanged
                    ifModifiedSince?.let { header(HttpHeaders.IfModifiedSince, it) }
                }
                response.readRawBytes() to response.headers[HttpHeaders.LastModified]
            } catch (e: RedirectResponseException) {
                // with expectSuccess, the status 304 is a "redirect" of the client
                if (e.response.status != HttpStatusCode.NotModified) {
                    throw e
                }
                null
            }
        } ?: return null

        val (archive, lastModified) = response
        ZipInputStream(archive.inputStream()).use { zip ->
            zip.nextEntry ?: throw IllegalStateException("ECB exchange rate archive $dataUrl is empty")
            return Download(EcbRateTable.parseCsv(zip, minDate), lastModified)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(EcbExchangeRateData::class.java)

        private const val FILE_NAME = "ecb-exchange-rates"

        private val DOWNLOAD_TIMEOUT: Duration = Duration.ofMinutes(1)

        /** The ZIP archive of the CSV file of all reference rates of the ECB since 1999. */
        const val DEFAULT_DATA_URL: String = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist.zip"

        // version of the stored data, increment it after incompatible changes of CachedEcbRates
        private const val DATA_VERSION = 1

        // a few days before the launch to support the lookup of the rates of previous days
        private val minDate: LocalDate = Marketplace.Birthday.add(0, 0, -7).toLocalDate()

    }
}
