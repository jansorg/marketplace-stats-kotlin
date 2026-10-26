/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.persistentCache

import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.PluginSale
import dev.ja.marketplace.client.model.PluginTrial
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Settings of the persistent caches of sales and trials.
 *
 * @param maxAge Data, which was fetched less than this ago, is used without fetching new data.
 * @param overlapDays Number of days before the end of the previous fetch, which are fetched again by an incremental fetch.
 *  Sales of these days may still change, e.g. because of refunds.
 * @param fullFetchInterval The complete history is fetched again in the background after this time,
 *  to find changes of older data.
 * @param backgroundRetryDelay Minimum time between two fetches of the complete history in the background,
 *  and between failed incremental fetches in the foreground.
 * @param backgroundUpdateMaxAge Data loaded from disk, which was fetched less than this ago, is returned immediately
 *  and updated in the background. Older data is updated before it's returned, e.g. after a long downtime.
 */
data class PersistentHistorySettings(
    val maxAge: Duration = 30.minutes,
    val overlapDays: Int = 45,
    val fullFetchInterval: Duration = 7.days,
    val backgroundRetryDelay: Duration = 10.minutes,
    val backgroundUpdateMaxAge: Duration = DEFAULT_BACKGROUND_UPDATE_MAX_AGE,
) {
    init {
        require(overlapDays >= 0)
    }

    companion object {
        /**
         * Default of [backgroundUpdateMaxAge]. An incremental fetch only takes a few requests,
         * but it avoids reports with outdated data after a long downtime.
         */
        val DEFAULT_BACKGROUND_UPDATE_MAX_AGE: Duration = 1.days
    }
}

/**
 * A type of history data, which is stored by [PersistentHistoryCache].
 *
 * @param T Type of the API model
 * @param C Type of the stored data
 * @param dataVersion Version of the stored data, increment it after incompatible changes of [C].
 */
internal class HistoryKind<T, C>(
    val name: String,
    val dataVersion: Int,
    val serializer: KSerializer<CachedHistory<C>>,
    val toCached: (T) -> C,
    val toModel: (C) -> T,
    val idOf: (T) -> String,
    val dateOf: (T) -> YearMonthDay,
) {
    companion object {
        val Sales = HistoryKind<PluginSale, CachedSale>(
            "sales", 1,
            CachedHistory.serializer(CachedSale.serializer()),
            PluginSale::toCached, CachedSale::toModel,
            PluginSale::ref, PluginSale::date,
        )

        val Trials = HistoryKind<PluginTrial, CachedTrial>(
            "trials", 1,
            CachedHistory.serializer(CachedTrial.serializer()),
            PluginTrial::toCached, CachedTrial::toModel,
            PluginTrial::referenceId, PluginTrial::date,
        )
    }
}

/**
 * Items of a history with the state of the fetches.
 */
internal data class HistorySnapshot<T>(
    val items: List<T>,
    val fetchedUntil: YearMonthDay,
    val lastFetchMillis: Long,
    val lastFullFetchMillis: Long,
)

/**
 * Logic of incremental fetches, without state.
 */
internal object HistoryMerger {
    /**
     * @return The date range to fetch. It's the complete history for [full] fetches or if there's no previous data.
     *  Otherwise, it starts [overlapDays] before the end of the previous fetch to find changes of recent items.
     */
    fun fetchRange(previous: HistorySnapshot<*>?, full: Boolean, today: YearMonthDay, overlapDays: Int): YearMonthDayRange {
        val firstDay = Marketplace.Birthday
        if (previous == null || full) {
            return YearMonthDayRange(firstDay, today)
        }

        val start = minOf(maxOf(previous.fetchedUntil.add(0, 0, -overlapDays), firstDay), today)
        return YearMonthDayRange(start, today)
    }

    /**
     * Merges fetched items into the previous items.
     * The fetched items replace all previous items in the fetched range, because the fetched data is complete for the range.
     * This removes items, which were deleted, and updates changed items. Previous items with the same id as a fetched item
     * are replaced, even if they're outside the range, e.g. because their date was changed.
     * The fetched items are kept as they are, including items with the same id, so that the result equals a fetch
     * of the complete history. Items are sorted by date, the order of items of the same day is kept.
     */
    fun <T> merge(
        previous: List<T>,
        fetched: List<T>,
        fetchedRange: YearMonthDayRange,
        idOf: (T) -> String,
        dateOf: (T) -> YearMonthDay,
    ): List<T> {
        val fetchedIds = fetched.mapTo(HashSet(fetched.size * 2), idOf)
        val kept = previous.filter { dateOf(it) !in fetchedRange && idOf(it) !in fetchedIds }
        return (kept + fetched).sortedBy(dateOf)
    }
}

/**
 * Persistent cache of the sales or trials of a plugin.
 *
 * - Without cached data, the complete history is fetched and stored.
 * - Data loaded from disk, which was fetched less than [PersistentHistorySettings.backgroundUpdateMaxAge] ago,
 *   is returned immediately. Newer data is fetched in the background and merged into the cached data.
 *   [onBackgroundUpdate] is called afterward.
 * - Older data loaded from disk is updated by an incremental fetch before it's returned.
 * - Data older than [PersistentHistorySettings.maxAge] is updated by an incremental fetch before it's returned.
 *   Concurrent calls wait for the running incremental fetch.
 * - The complete history is fetched in the background every [PersistentHistorySettings.fullFetchInterval].
 *   Incremental fetches still update the recent data while it runs or if it fails.
 * - After [invalidate], the data on disk is ignored and the complete history is fetched again.
 *
 * Failures of background fetches are logged and the previous data is kept. If there's cached data,
 * failures of incremental fetches in the foreground are logged, too, and the cached data is returned.
 * Without cached data, failures are thrown.
 */
internal class PersistentHistoryCache<T, C>(
    private val store: PersistentCacheStore,
    private val kind: HistoryKind<T, C>,
    private val fileName: String,
    private val scope: CoroutineScope,
    private val settings: PersistentHistorySettings,
    private val fetcher: suspend (YearMonthDayRange) -> List<T>,
    private val onBackgroundUpdate: () -> Unit = {},
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val today: () -> YearMonthDay = YearMonthDay::now,
    ignoreStoredData: Boolean = false,
) {
    private val mutex = Mutex()
    private var snapshot: HistorySnapshot<T>? = null
    private var isDiskChecked = ignoreStoredData

    // incremented by invalidate, results of background fetches started before are discarded
    private val generation = AtomicInteger(0)
    private var seenGeneration = 0

    // incremental fetch in the background after the data was loaded from disk
    private var incrementalJob: Job? = null

    // time of the latest failed incremental fetch in the foreground
    private var lastIncrementalFailureMillis: Long? = null

    private var fullFetchJob: Job? = null
    private var lastFullFetchStartMillis: Long? = null

    // start of the earliest range, which was fetched incrementally while the full fetch is running
    private var incrementalStartDuringFullFetch: YearMonthDay? = null

    // reading and writing files blocks the thread
    suspend fun get(): List<T> = withContext(Dispatchers.IO) {
        var result = mutex.withLock { getLocked() }
        while (result is GetResult.Await) {
            // wait outside the lock, the job needs it to store its result
            result.job.join()
            result = mutex.withLock { getLocked() }
        }
        (result as GetResult.Items).items
    }

    private sealed interface GetResult<out T> {
        class Items<T>(val items: List<T>) : GetResult<T>
        class Await(val job: Job) : GetResult<Nothing>
    }

    // must be called with the lock held
    private suspend fun getLocked(): GetResult<T> {
        val currentGeneration = generation.get()
        if (currentGeneration != seenGeneration) {
            seenGeneration = currentGeneration
            snapshot = null
            isDiskChecked = true
            incrementalJob = null
            lastIncrementalFailureMillis = null
            fullFetchJob = null
            lastFullFetchStartMillis = null
            incrementalStartDuringFullFetch = null
        }

        if (snapshot == null && !isDiskChecked) {
            isDiskChecked = true
            val stored = readStored()
            if (stored != null) {
                snapshot = stored
                if (!isOutdated(stored)) {
                    // The recent days are fetched again after each start, also if the stored data is fresh,
                    // because JetBrains changes recent data, e.g. when sales are refunded.
                    startIncrementalFetch()
                    startFullFetchIfDue(stored)
                    return GetResult.Items(stored.items)
                }
                logger.info("Cached ${kind.name} in ${store.directory} are outdated, updating them before they're used")
            }
        }

        val current = snapshot ?: return GetResult.Items(fetchAndStore(null, full = true).items)
        if (isStale(current)) {
            incrementalJob?.takeIf { it.isActive }?.let { return GetResult.Await(it) }
        }

        val updated = when {
            isStale(current) -> updateIncrementally(current)
            else -> current
        }
        startFullFetchIfDue(updated)
        return GetResult.Items(updated.items)
    }

    /**
     * Drops the cached data in memory and ignores the data on disk. The next call of [get] fetches the complete history.
     */
    fun invalidate() {
        generation.incrementAndGet()
        incrementalJob?.cancel()
        fullFetchJob?.cancel()
    }

    /**
     * Fetches the recent data in the background, also if it isn't stale yet, and waits until it's done, e.g. for the
     * automatic refresh. Requests use the cached data meanwhile. Nothing is fetched without data in memory.
     */
    suspend fun refresh() {
        val job = mutex.withLock {
            val current = snapshot
            if (current == null || generation.get() != seenGeneration) {
                return@withLock null
            }
            startIncrementalFetch()
            startFullFetchIfDue(current)
            incrementalJob
        }
        job?.join()
    }

    /**
     * Waits until the current background fetches are done, for tests.
     */
    internal suspend fun awaitBackgroundFetch() {
        val jobs = mutex.withLock { listOfNotNull(incrementalJob, fullFetchJob) }
        jobs.joinAll()
    }

    private fun isStale(data: HistorySnapshot<T>): Boolean {
        return currentTimeMillis() - data.lastFetchMillis >= settings.maxAge.inWholeMilliseconds
    }

    private fun isOutdated(data: HistorySnapshot<T>): Boolean {
        return currentTimeMillis() - data.lastFetchMillis >= settings.backgroundUpdateMaxAge.inWholeMilliseconds
    }

    private fun isFullFetchDue(data: HistorySnapshot<T>): Boolean {
        return currentTimeMillis() - data.lastFullFetchMillis >= settings.fullFetchInterval.inWholeMilliseconds
    }

    private fun isRetryDelayed(lastAttemptMillis: Long?): Boolean {
        return lastAttemptMillis != null && currentTimeMillis() - lastAttemptMillis < settings.backgroundRetryDelay.inWholeMilliseconds
    }

    // must be called with the lock held
    private suspend fun fetchAndStore(previous: HistorySnapshot<T>?, full: Boolean): HistorySnapshot<T> {
        val range = HistoryMerger.fetchRange(previous, full, today(), settings.overlapDays)
        val fetchTime = currentTimeMillis()
        val fetched = fetcher(range)
        val isFull = full || previous == null
        val updated = merged(previous, fetched, range, isFull, fetchTime)
        if (!isFull) {
            onIncrementalFetch(range, fetchTime)
        }
        snapshot = updated
        if (isFull || isStoredDataChanged(previous!!, updated)) {
            write(updated)
        }
        return updated
    }

    // must be called with the lock held
    private fun onIncrementalFetch(range: YearMonthDayRange, fetchTime: Long) {
        val fullFetchStart = lastFullFetchStartMillis
        if (fullFetchJob?.isActive == true && fullFetchStart != null && fetchTime >= fullFetchStart) {
            incrementalStartDuringFullFetch = minOf(incrementalStartDuringFullFetch ?: range.start, range.start)
        }
    }

    /**
     * Fetches recent data in the foreground. If it fails, the cached data is returned
     * and no new attempt is made in the foreground before [PersistentHistorySettings.backgroundRetryDelay].
     */
    // must be called with the lock held
    private suspend fun updateIncrementally(current: HistorySnapshot<T>): HistorySnapshot<T> {
        if (isRetryDelayed(lastIncrementalFailureMillis)) {
            return current
        }

        return try {
            fetchAndStore(current, full = false).also {
                lastIncrementalFailureMillis = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastIncrementalFailureMillis = currentTimeMillis()
            logger.warn("Unable to update cached ${kind.name} in ${store.directory}, the cached data is used: $e")
            current
        }
    }

    // must be called with the lock held
    private fun startIncrementalFetch() {
        if (incrementalJob?.isActive == true) {
            return
        }

        val startGeneration = seenGeneration
        incrementalJob = scope.launch {
            try {
                val previous = mutex.withLock { snapshot } ?: return@launch
                val range = HistoryMerger.fetchRange(previous, false, today(), settings.overlapDays)
                val fetchTime = currentTimeMillis()
                val fetched = fetcher(range)

                val isUpdated = mutex.withLock {
                    val latest = snapshot
                    // discard the result if the cache was invalidated or if newer data was fetched in the meantime
                    if (generation.get() != startGeneration || latest == null || latest.lastFetchMillis > fetchTime) {
                        return@withLock false
                    }

                    val updated = merged(latest, fetched, range, false, fetchTime)
                    onIncrementalFetch(range, fetchTime)
                    snapshot = updated
                    if (isStoredDataChanged(latest, updated)) {
                        write(updated)
                    }
                    // the data derived from the items, e.g. the licenses, is only outdated if the items changed
                    updated.items != latest.items
                }

                if (isUpdated) {
                    logger.info("Updated cached ${kind.name} in ${store.directory}")
                    onBackgroundUpdate()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Unable to update cached ${kind.name} in ${store.directory}, the cached data is kept: $e")
            }
        }
    }

    // must be called with the lock held
    private fun startFullFetchIfDue(current: HistorySnapshot<T>) {
        if (!isFullFetchDue(current) || fullFetchJob?.isActive == true || isRetryDelayed(lastFullFetchStartMillis)) {
            return
        }

        val fetchTime = currentTimeMillis()
        lastFullFetchStartMillis = fetchTime
        incrementalStartDuringFullFetch = null

        val startGeneration = seenGeneration
        fullFetchJob = scope.launch {
            try {
                val range = HistoryMerger.fetchRange(null, true, today(), settings.overlapDays)
                val fetched = fetcher(range)

                val isUpdated = mutex.withLock {
                    val latest = snapshot
                    if (generation.get() != startGeneration || latest == null) {
                        return@withLock false
                    }

                    var updated = merged(latest, fetched, range, true, fetchTime)
                    // keep the data of incremental fetches, which were done after the start of the full fetch
                    val recentStart = incrementalStartDuringFullFetch
                    if (recentStart != null && recentStart <= latest.fetchedUntil) {
                        val recentRange = YearMonthDayRange(recentStart, latest.fetchedUntil)
                        val recentItems = latest.items.filter { kind.dateOf(it) in recentRange }
                        updated = HistorySnapshot(
                            HistoryMerger.merge(updated.items, recentItems, recentRange, kind.idOf, kind.dateOf),
                            fetchedUntil = latest.fetchedUntil,
                            lastFetchMillis = latest.lastFetchMillis,
                            lastFullFetchMillis = fetchTime,
                        )
                    }

                    snapshot = updated
                    write(updated)
                    true
                }

                if (isUpdated) {
                    logger.info("Updated cached ${kind.name} in ${store.directory} with the complete history")
                    onBackgroundUpdate()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Unable to fetch the complete history of cached ${kind.name} in ${store.directory}, the cached data is kept: $e")
            }
        }
    }

    /**
     * @return If the stored file is outdated: if the items or the fetched days changed. An unchanged fetch of the
     *  recent days isn't written, a restart fetches the recent days again.
     */
    private fun isStoredDataChanged(previous: HistorySnapshot<T>, updated: HistorySnapshot<T>): Boolean {
        return updated.fetchedUntil != previous.fetchedUntil || updated.items != previous.items
    }

    private fun merged(
        previous: HistorySnapshot<T>?,
        fetched: List<T>,
        range: YearMonthDayRange,
        isFull: Boolean,
        fetchTime: Long
    ): HistorySnapshot<T> {
        val items = HistoryMerger.merge(previous?.items ?: emptyList(), fetched, range, kind.idOf, kind.dateOf)
        return HistorySnapshot(
            items,
            fetchedUntil = range.end,
            lastFetchMillis = fetchTime,
            lastFullFetchMillis = if (isFull) fetchTime else previous?.lastFullFetchMillis ?: fetchTime,
        )
    }

    private fun readStored(): HistorySnapshot<T>? {
        val stored = store.read(fileName, kind.dataVersion, kind.serializer) ?: return null
        return try {
            HistorySnapshot(
                stored.items.map(kind.toModel),
                cachedDate(stored.fetchedUntil),
                stored.lastFetchMillis,
                stored.lastFullFetchMillis,
            ).also {
                logger.info("Loaded ${it.items.size} cached ${kind.name} from ${store.directory}")
            }
        } catch (e: Exception) {
            logger.warn("Dropping invalid cached ${kind.name} in ${store.directory}: $e")
            store.delete(fileName)
            null
        }
    }

    private fun write(data: HistorySnapshot<T>) {
        val cached = CachedHistory(
            data.fetchedUntil.toCached(),
            data.lastFetchMillis,
            data.lastFullFetchMillis,
            data.items.map(kind.toCached),
        )
        store.write(fileName, kind.dataVersion, kind.serializer, cached)
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(PersistentHistoryCache::class.java)
    }
}
