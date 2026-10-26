/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.persistentCache

import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.PluginSale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class PersistentHistoryCacheTest {
    @TempDir
    lateinit var tempDir: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = PersistentHistorySettings(maxAge = 30.minutes, overlapDays = 10, fullFetchInterval = 7.days, backgroundRetryDelay = 10.minutes)

    private var nowMillis = 1_000_000_000_000L
    private var today = day(2025, 3, 15)

    // sales available from the API
    private val serverSales = CopyOnWriteArrayList<PluginSale>()
    private val fetchedRanges = CopyOnWriteArrayList<YearMonthDayRange>()
    @Volatile
    private var failFetch = false

    // only fetches of the complete history fail
    @Volatile
    private var failFullFetch = false

    // fetches of the complete history wait for this, they return the data available when they started
    @Volatile
    private var fullFetchGate: CompletableDeferred<Unit>? = null

    // incremental fetches wait for this
    @Volatile
    private var incrementalFetchGate: CompletableDeferred<Unit>? = null
    private val backgroundUpdates = AtomicInteger()

    private val store by lazy { PersistentCacheStore(tempDir) }

    @AfterEach
    fun cancelScope() {
        scope.cancel()
    }

    private fun createCache(ignoreStoredData: Boolean = false) = PersistentHistoryCache(
        store,
        HistoryKind.Sales,
        "plugin-1-sales",
        scope,
        settings,
        fetcher = { range ->
            fetchedRanges += range
            if (failFetch) throw IllegalStateException("fetch failed")
            val result = serverSales.filter { it.date in range }
            if (range.start == Marketplace.Birthday) {
                fullFetchGate?.await()
                if (failFullFetch) throw IllegalStateException("full fetch failed")
            } else {
                incrementalFetchGate?.await()
            }
            result
        },
        onBackgroundUpdate = { backgroundUpdates.incrementAndGet() },
        currentTimeMillis = { nowMillis },
        today = { today },
        ignoreStoredData = ignoreStoredData,
    )

    private fun fullRange(end: YearMonthDay = today) = YearMonthDayRange(Marketplace.Birthday, end)

    @Test
    suspend fun firstLoadFetchesCompleteHistoryAndStoresIt() {
        serverSales += listOf(testSale("a", day(2020, 1, 1)), testSale("b", day(2025, 3, 1)))

        val cache = createCache()
        assertEquals(serverSales, cache.get())
        assertEquals(listOf(fullRange()), fetchedRanges)
        assertTrue(store.file("plugin-1-sales").exists())

        // cached in memory
        assertEquals(serverSales, cache.get())
        assertEquals(1, fetchedRanges.size)
    }

    @Test
    suspend fun unchangedBackgroundFetchKeepsTheDerivedData() {
        serverSales += listOf(testSale("a", day(2020, 1, 1)), testSale("b", day(2025, 3, 10)))
        val cache = createCache()
        cache.get()
        val stored = store.file("plugin-1-sales").toFile().lastModified()

        // the recent days didn't change, e.g. the licenses derived from the sales are still valid
        nowMillis += 30.minutes.inWholeMilliseconds
        cache.refresh()
        assertEquals(0, backgroundUpdates.get())
        assertEquals(stored, store.file("plugin-1-sales").toFile().lastModified(), "the unchanged data isn't written")

        serverSales += testSale("c", day(2025, 3, 14))
        nowMillis += 30.minutes.inWholeMilliseconds
        cache.refresh()
        assertEquals(1, backgroundUpdates.get())
        assertEquals(listOf("a", "b", "c"), cache.get().map { it.ref })
    }

    @Test
    suspend fun restartFetchesTheRecentDaysOfFreshDataInBackground() {
        serverSales += listOf(testSale("a", day(2020, 1, 1)), testSale("b", day(2025, 3, 10)))
        createCache().get()
        fetchedRanges.clear()

        // a refund shortly before the restart
        serverSales.removeIf { it.ref == "b" }
        nowMillis += 5.minutes.inWholeMilliseconds
        val restarted = createCache()
        assertEquals(listOf("a", "b"), restarted.get().map { it.ref }, "stored data must be returned immediately")

        restarted.awaitBackgroundFetch()
        assertEquals(listOf(YearMonthDayRange(day(2025, 3, 5), today)), fetchedRanges, "only the recent days are fetched")
        assertEquals(listOf("a"), restarted.get().map { it.ref })
    }

    @Test
    suspend fun restartUsesStoredDataAndUpdatesInBackground() {
        serverSales += listOf(testSale("a", day(2020, 1, 1)), testSale("b", day(2025, 3, 10)))
        createCache().get()
        fetchedRanges.clear()

        // new sales and a refund after the restart
        serverSales.removeIf { it.ref == "b" }
        serverSales += listOf(testSale("c", day(2025, 3, 16)), testSale("d", day(2025, 3, 17)))
        nowMillis += 2.hours.inWholeMilliseconds
        today = day(2025, 3, 17)

        val restarted = createCache()
        assertEquals(listOf("a", "b"), restarted.get().map { it.ref }, "stored data must be returned immediately")

        restarted.awaitBackgroundFetch()
        assertEquals(listOf(YearMonthDayRange(day(2025, 3, 5), today)), fetchedRanges, "incremental fetch with overlap")
        assertEquals(1, backgroundUpdates.get())
        assertEquals(listOf("a", "c", "d"), restarted.get().map { it.ref })

        // the merged data was stored, a restart fetches the recent days again
        nowMillis += 1.minutes.inWholeMilliseconds
        val restartedAgain = createCache()
        assertEquals(listOf("a", "c", "d"), restartedAgain.get().map { it.ref })
        restartedAgain.awaitBackgroundFetch()
        // the overlap starts before the end of the previous fetch
        assertEquals(listOf(YearMonthDayRange(day(2025, 3, 5), today), YearMonthDayRange(day(2025, 3, 7), today)), fetchedRanges)
    }

    @Test
    suspend fun outdatedDataInMemoryIsUpdatedIncrementally() {
        serverSales += testSale("a", day(2025, 3, 1))
        val cache = createCache()
        cache.get()

        serverSales += testSale("b", day(2025, 3, 20))
        today = day(2025, 3, 20)
        nowMillis += 31.minutes.inWholeMilliseconds

        assertEquals(listOf("a", "b"), cache.get().map { it.ref })
        assertEquals(listOf(fullRange(day(2025, 3, 15)), YearMonthDayRange(day(2025, 3, 5), today)), fetchedRanges)
        assertEquals(0, backgroundUpdates.get())
    }

    @Test
    suspend fun completeHistoryIsFetchedInBackgroundAfterInterval() {
        serverSales += listOf(testSale("old", day(2020, 1, 1)), testSale("new", day(2025, 3, 1)))
        val cache = createCache()
        cache.get()

        // a change of old data is only found by a fetch of the complete history
        serverSales.removeIf { it.ref == "old" }
        nowMillis += 8.days.inWholeMilliseconds
        today = day(2025, 3, 23)

        assertEquals(listOf("old", "new"), cache.get().map { it.ref })
        cache.awaitBackgroundFetch()
        assertEquals(fullRange(), fetchedRanges.last())
        assertEquals(listOf("new"), cache.get().map { it.ref })
    }

    @Test
    suspend fun invalidateFetchesCompleteHistoryAndIgnoresStoredData() {
        serverSales += testSale("a", day(2020, 1, 1))
        val cache = createCache()
        cache.get()

        serverSales.clear()
        serverSales += testSale("b", day(2020, 1, 1))
        cache.invalidate()
        assertEquals(listOf("b"), cache.get().map { it.ref })
        assertEquals(listOf(fullRange(), fullRange()), fetchedRanges)

        // the new data was stored, a restart only fetches the recent days again
        val restarted = createCache()
        assertEquals(listOf("b"), restarted.get().map { it.ref })
        restarted.awaitBackgroundFetch()
        assertEquals(2, fetchedRanges.count { it == fullRange() })
    }

    @Test
    suspend fun ignoredStoredData() {
        serverSales += testSale("a", day(2020, 1, 1))
        createCache().get()

        createCache(ignoreStoredData = true).get()
        assertEquals(listOf(fullRange(), fullRange()), fetchedRanges)
    }

    @Test
    suspend fun failedBackgroundFetchKeepsData() {
        serverSales += testSale("a", day(2020, 1, 1))
        createCache().get()

        failFetch = true
        nowMillis += 1.hours.inWholeMilliseconds
        val restarted = createCache()
        assertEquals(listOf("a"), restarted.get().map { it.ref })
        restarted.awaitBackgroundFetch()
        assertEquals(0, backgroundUpdates.get())
        assertEquals(2, fetchedRanges.size)

        // no new background fetch before the retry delay, the data is outdated, but a full fetch is not due
        // and an incremental fetch is done in the foreground
        failFetch = false
        serverSales += testSale("b", day(2025, 3, 15))
        assertEquals(listOf("a", "b"), restarted.get().map { it.ref })
    }

    @Test
    suspend fun failedForegroundFetchThrows() {
        failFetch = true
        val cache = createCache()
        assertInstanceOf(IllegalStateException::class.java, runCatching { cache.get() }.exceptionOrNull())

        failFetch = false
        serverSales += testSale("a", day(2020, 1, 1))
        assertEquals(listOf("a"), cache.get().map { it.ref })
    }

    @Test
    suspend fun corruptStoredDataIsDropped() {
        serverSales += testSale("a", day(2020, 1, 1))
        createCache().get()
        store.file("plugin-1-sales").writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13))

        assertEquals(listOf("a"), createCache().get().map { it.ref })
        assertEquals(listOf(fullRange(), fullRange()), fetchedRanges)
    }

    @Test
    suspend fun storedDataOfOtherVersionIsDropped() {
        serverSales += testSale("a", day(2020, 1, 1))
        val kind = HistoryKind.Sales
        store.write("plugin-1-sales", kind.dataVersion + 1, kind.serializer, CachedHistory(0, nowMillis, nowMillis, emptyList()))

        assertEquals(listOf("a"), createCache().get().map { it.ref })
        assertEquals(listOf(fullRange()), fetchedRanges)
    }

    @Test
    suspend fun failingFullFetchDoesNotStopIncrementalUpdates() {
        serverSales += listOf(testSale("old", day(2020, 1, 1)), testSale("a", day(2025, 3, 1)))
        val cache = createCache()
        cache.get()

        // e.g. a gateway timeout of an old chunk
        failFullFetch = true
        serverSales += testSale("b", day(2025, 3, 20))
        today = day(2025, 3, 20)
        nowMillis += 8.days.inWholeMilliseconds

        assertEquals(listOf("old", "a", "b"), cache.get().map { it.ref })
        cache.awaitBackgroundFetch()
        assertEquals(listOf(YearMonthDayRange(day(2025, 3, 5), today), fullRange()), fetchedRanges.drop(1))
        assertEquals(0, backgroundUpdates.get())

        // new data is still found, the full fetch is retried after the retry delay
        serverSales += testSale("c", day(2025, 3, 21))
        today = day(2025, 3, 21)
        nowMillis += 31.minutes.inWholeMilliseconds
        assertEquals(listOf("old", "a", "b", "c"), cache.get().map { it.ref })
        cache.awaitBackgroundFetch()
        assertEquals(listOf(YearMonthDayRange(day(2025, 3, 10), today), fullRange()), fetchedRanges.drop(3))

        // the full fetch works again
        failFullFetch = false
        serverSales.removeIf { it.ref == "old" }
        nowMillis += 31.minutes.inWholeMilliseconds
        assertEquals(listOf("old", "a", "b", "c"), cache.get().map { it.ref })
        cache.awaitBackgroundFetch()
        assertEquals(listOf("a", "b", "c"), cache.get().map { it.ref })
        assertEquals(1, backgroundUpdates.get())
    }

    @Test
    suspend fun incrementalFetchesDuringFullFetchAreKept() {
        serverSales += listOf(testSale("old", day(2020, 1, 1)), testSale("a", day(2025, 3, 1)))
        val cache = createCache()
        cache.get()

        serverSales.removeIf { it.ref == "old" }
        nowMillis += 8.days.inWholeMilliseconds
        val gate = CompletableDeferred<Unit>()
        fullFetchGate = gate
        assertEquals(listOf("old", "a"), cache.get().map { it.ref })

        // a sale, which isn't part of the running full fetch, but of an incremental fetch while it's running
        serverSales += testSale("b", day(2025, 3, 15))
        nowMillis += 31.minutes.inWholeMilliseconds
        assertEquals(listOf("old", "a", "b"), cache.get().map { it.ref })

        gate.complete(Unit)
        cache.awaitBackgroundFetch()
        assertEquals(listOf("a", "b"), cache.get().map { it.ref })
        assertEquals(1, backgroundUpdates.get())

        // the full fetch was stored, but the time of the latest fetch wasn't reset:
        // a restart only fetches the recent days again
        val countBefore = fetchedRanges.size
        nowMillis += 1.minutes.inWholeMilliseconds
        val restarted = createCache()
        assertEquals(listOf("a", "b"), restarted.get().map { it.ref })
        restarted.awaitBackgroundFetch()
        assertTrue(fetchedRanges.drop(countBefore).none { it.start == Marketplace.Birthday }, "no fetch of the complete history")
    }

    @Test
    suspend fun outdatedStoredDataIsUpdatedBeforeItsReturned() {
        serverSales += listOf(testSale("a", day(2020, 1, 1)), testSale("b", day(2025, 3, 10)))
        createCache().get()
        fetchedRanges.clear()

        // a restart after a long downtime
        serverSales += testSale("c", day(2025, 3, 16))
        nowMillis += 2.days.inWholeMilliseconds
        today = day(2025, 3, 17)

        val restarted = createCache()
        assertEquals(listOf("a", "b", "c"), restarted.get().map { it.ref })
        assertEquals(listOf(YearMonthDayRange(day(2025, 3, 5), today)), fetchedRanges)
        restarted.awaitBackgroundFetch()
        assertEquals(0, backgroundUpdates.get())
        assertEquals(1, fetchedRanges.size)
    }

    @Test
    suspend fun outdatedStoredDataIsReturnedIfTheUpdateFails() {
        serverSales += listOf(testSale("a", day(2020, 1, 1)), testSale("b", day(2025, 3, 10)))
        createCache().get()
        fetchedRanges.clear()

        failFetch = true
        serverSales += testSale("c", day(2025, 3, 16))
        nowMillis += 2.days.inWholeMilliseconds
        today = day(2025, 3, 17)

        val restarted = createCache()
        assertEquals(listOf("a", "b"), restarted.get().map { it.ref })
        assertEquals(1, fetchedRanges.size)

        // no new attempt before the retry delay
        nowMillis += 1.minutes.inWholeMilliseconds
        assertEquals(listOf("a", "b"), restarted.get().map { it.ref })
        assertEquals(1, fetchedRanges.size)

        failFetch = false
        nowMillis += 10.minutes.inWholeMilliseconds
        assertEquals(listOf("a", "b", "c"), restarted.get().map { it.ref })
    }

    @Test
    suspend fun concurrentCallsWaitForRunningIncrementalFetch() = coroutineScope {
        serverSales += listOf(testSale("a", day(2020, 1, 1)), testSale("b", day(2025, 3, 10)))
        createCache().get()
        fetchedRanges.clear()

        serverSales += testSale("c", day(2025, 3, 16))
        nowMillis += 2.hours.inWholeMilliseconds
        today = day(2025, 3, 17)
        val gate = CompletableDeferred<Unit>()
        incrementalFetchGate = gate

        val restarted = createCache()
        assertEquals(listOf("a", "b"), restarted.get().map { it.ref }, "stored data must be returned immediately")

        // the data is outdated, the second call waits for the background fetch instead of starting another one
        val second = async(Dispatchers.Default) { restarted.get() }
        delay(100)
        assertFalse(second.isCompleted)

        gate.complete(Unit)
        assertEquals(listOf("a", "b", "c"), second.await().map { it.ref })
        assertEquals(listOf(YearMonthDayRange(day(2025, 3, 5), today)), fetchedRanges)
    }
}
