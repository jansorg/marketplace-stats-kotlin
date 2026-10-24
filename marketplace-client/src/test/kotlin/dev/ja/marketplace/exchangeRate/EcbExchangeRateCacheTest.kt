/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import dev.ja.marketplace.client.persistentCache.PersistentCacheStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeBytes

class EcbExchangeRateCacheTest {
    @TempDir
    lateinit var tempDir: Path

    private fun table(csv: String) = EcbRateTable.parseCsv(csv.trimIndent().byteInputStream())

    private val olderTable = table(
        """
        Date,USD,JPY,CYP,
        2024-04-12,1.0652,163.16,N/A,
        2024-04-11,1.0729,164.29,N/A,
        2008-01-02,1.4,160.0,0.58,
        """
    )

    private val newerTable = table(
        """
        Date,USD,JPY,GBP,
        2024-04-16,1.0637,164.54,0.85,
        2024-04-15,1.0656,164.62,0.85365,
        2024-04-12,1.0655,163.17,0.85435,
        """
    )

    private fun date(value: String) = LocalDate.parse(value)

    @Test
    fun mergeAppendsNewDays() {
        val merged = olderTable.mergedWith(newerTable)
        assertEquals(date("2008-01-02"), merged.firstDate)
        assertEquals(date("2024-04-16"), merged.lastDate)
        assertEquals(BigDecimal("1.0637"), merged.euroRate("USD", date("2024-04-16")))
        assertEquals(BigDecimal("1.0656"), merged.euroRate("USD", date("2024-04-15")))
    }

    @Test
    fun mergeReplacesRatesOfNewerTable() {
        val merged = olderTable.mergedWith(newerTable)
        assertEquals(BigDecimal("1.0655"), merged.euroRate("USD", date("2024-04-12")))
        assertEquals(BigDecimal("163.17"), merged.euroRate("JPY", date("2024-04-12")))
    }

    @Test
    fun mergeKeepsOlderRates() {
        val merged = olderTable.mergedWith(newerTable)
        assertEquals(BigDecimal("1.0729"), merged.euroRate("USD", date("2024-04-11")))
        assertEquals(BigDecimal("1.4"), merged.euroRate("USD", date("2008-01-02")))
        // currency, which is only available in the older table
        assertEquals(BigDecimal("0.58"), merged.euroRate("CYP", date("2008-01-02")))
        assertNull(merged.euroRate("CYP", date("2024-04-12")))
    }

    @Test
    fun mergeAddsNewCurrencies() {
        val merged = olderTable.mergedWith(newerTable)
        assertEquals(BigDecimal("0.85435"), merged.euroRate("GBP", date("2024-04-12")))
        assertNull(merged.euroRate("GBP", date("2024-04-11")))
    }

    @Test
    fun roundTrip() {
        val store = PersistentCacheStore(tempDir)
        val data = EcbExchangeRateData(downloader = { olderTable.mergedWith(newerTable) })
        data.enablePersistentCache(store)
        val table = data.get()

        val restored = EcbExchangeRateData(downloader = { throw IllegalStateException("must not download") })
        restored.enablePersistentCache(store)
        val restoredTable = restored.get()

        assertEquals(table.firstDate, restoredTable.firstDate)
        assertEquals(table.lastDate, restoredTable.lastDate)
        for (currency in listOf("USD", "JPY", "CYP", "GBP", "CHF")) {
            var day = table.firstDate!!
            while (day <= table.lastDate) {
                assertEquals(table.euroRate(currency, day), restoredTable.euroRate(currency, day), "$currency $day")
                day = day.plusDays(1)
            }
        }
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }

    @Test
    fun outdatedStoredRatesAreUsedAndUpdatedInBackground() {
        val store = PersistentCacheStore(tempDir)
        val clock = MutableClock(Instant.parse("2024-04-12T18:00:00Z"))
        val downloads = AtomicInteger()

        val first = EcbExchangeRateData(clock = clock, downloader = { downloads.incrementAndGet(); olderTable })
        first.enablePersistentCache(store)
        assertEquals(date("2024-04-12"), first.get().lastDate)
        assertEquals(1, downloads.get())

        // restart after a few days
        clock.now = clock.now.plus(Duration.ofDays(4))
        val restarted = EcbExchangeRateData(clock = clock, downloader = { downloads.incrementAndGet(); newerTable })
        restarted.enablePersistentCache(store)

        assertEquals(date("2024-04-12"), restarted.get().lastDate, "outdated rates are returned immediately")
        restarted.awaitBackgroundRefresh()
        assertEquals(2, downloads.get())

        val updated = restarted.get()
        assertEquals(date("2024-04-16"), updated.lastDate)
        assertEquals(BigDecimal("1.0729"), updated.euroRate("USD", date("2024-04-11")), "older rates are kept")

        // the merged rates were stored
        val again = EcbExchangeRateData(clock = clock, downloader = { throw IllegalStateException("must not download") })
        again.enablePersistentCache(store)
        assertEquals(date("2024-04-16"), again.get().lastDate)
        assertEquals(date("2024-04-11"), again.get().closestDate(date("2024-04-11")))
    }

    @Test
    fun failedBackgroundUpdateKeepsRates() {
        val store = PersistentCacheStore(tempDir)
        val clock = MutableClock(Instant.parse("2024-04-12T18:00:00Z"))
        EcbExchangeRateData(clock = clock, downloader = { olderTable }).apply { enablePersistentCache(store) }.get()

        clock.now = clock.now.plus(Duration.ofDays(1))
        val downloads = AtomicInteger()
        val restarted = EcbExchangeRateData(clock = clock, downloader = { downloads.incrementAndGet(); throw IllegalStateException("offline") })
        restarted.enablePersistentCache(store)
        assertEquals(date("2024-04-12"), restarted.get().lastDate)
        restarted.awaitBackgroundRefresh()
        assertEquals(date("2024-04-12"), restarted.get().lastDate)
        restarted.awaitBackgroundRefresh()
        assertEquals(1, downloads.get(), "no retry before the retry delay")
    }

    @Test
    fun invalidateDownloadsRates() {
        val store = PersistentCacheStore(tempDir)
        val downloads = AtomicInteger()
        val data = EcbExchangeRateData(downloader = { downloads.incrementAndGet(); newerTable })
        data.enablePersistentCache(store)
        data.get()
        data.invalidate()
        data.get()
        assertEquals(2, downloads.get())
    }

    @Test
    fun corruptStoredRatesAreDropped() {
        val store = PersistentCacheStore(tempDir)
        EcbExchangeRateData(downloader = { olderTable }).apply { enablePersistentCache(store) }.get()
        store.directory.listDirectoryEntries().single().writeBytes(byteArrayOf(0, 1, 2))

        val downloads = AtomicInteger()
        val data = EcbExchangeRateData(downloader = { downloads.incrementAndGet(); newerTable })
        data.enablePersistentCache(store)
        assertEquals(date("2024-04-16"), data.get().lastDate)
        assertEquals(1, downloads.get())
    }

    @Test
    fun withoutPersistentCache() {
        val downloads = AtomicInteger()
        val data = EcbExchangeRateData(downloader = { downloads.incrementAndGet(); newerTable })
        assertEquals(date("2024-04-16"), data.get().lastDate)
        assertEquals(date("2024-04-16"), data.get().lastDate)
        assertEquals(1, downloads.get())
        assertEquals(emptyList<Path>(), tempDir.listDirectoryEntries())
    }
}
