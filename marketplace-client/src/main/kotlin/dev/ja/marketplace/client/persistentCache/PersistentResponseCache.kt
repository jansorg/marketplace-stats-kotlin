/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

@file:OptIn(ExperimentalSerializationApi::class)

package dev.ja.marketplace.client.persistentCache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoNumber
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * Stored responses of a file of the [PersistentResponseCache].
 */
@Serializable
internal class CachedResponses(
    @ProtoNumber(1) val entries: List<CachedResponse> = emptyList(),
)

/**
 * @param json The response as JSON, so that changes of the API model don't require a new data version.
 *  Responses, which can't be read anymore, are fetched again.
 * @param maxAgeMillis The age, after which the response is outdated, `0` for the default of the cache, e.g. of the
 *  responses stored before it existed.
 */
@Serializable
internal class CachedResponse(
    @ProtoNumber(1) val key: String,
    @ProtoNumber(2) val fetchedMillis: Long,
    @ProtoNumber(3) val json: String,
    @ProtoNumber(4) val maxAgeMillis: Long = 0,
)

/**
 * Stores API responses on disk, e.g. downloads or the products of JetBrains. A response is fetched again,
 * if it's older than [maxAge] or the maximum age of the request, also after a restart, see [get]. The responses are grouped into files, e.g. one file per plugin,
 * a file is written shortly after its responses changed.
 */
internal class PersistentResponseCache(
    private val store: PersistentCacheStore,
    private val scope: CoroutineScope,
    private val maxAge: Duration = 1.days,
    private val writeDelay: Duration = 2.seconds,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val fetchedMillis: Long, val json: String, val maxAgeMillis: Long) {
        fun isOutdated(now: Long): Boolean = now - fetchedMillis >= maxAgeMillis
    }

    private class FileEntries(val entries: ConcurrentHashMap<String, Entry>) {
        val isWriteScheduled = AtomicBoolean(false)
    }

    private val files = ConcurrentHashMap<String, FileEntries>()

    // responses fetched before this time are outdated, set by invalidate
    @Volatile
    private var invalidatedMillis = 0L

    /**
     * @param forceFetch If the response is fetched, also if a stored response isn't outdated yet, e.g. by the automatic refresh.
     * @param maxAge The age, after which the response is fetched again, e.g. longer for downloads of the past, which
     *  don't change anymore. It's stored with the response.
     */
    suspend fun <T> get(
        fileName: String,
        key: String,
        serializer: KSerializer<T>,
        forceFetch: Boolean = false,
        maxAge: Duration = this.maxAge,
        loader: suspend () -> T,
    ): Stored<T> {
        val file = files[fileName] ?: withContext(Dispatchers.IO) { files.computeIfAbsent(fileName, ::readStored) }
        val now = currentTimeMillis()
        val cached = file.entries[key]
        if (!forceFetch && cached != null && cached.fetchedMillis >= invalidatedMillis && now - cached.fetchedMillis < maxAge.inWholeMilliseconds) {
            try {
                val stored = Stored(json.decodeFromString(serializer, cached.json), cached.fetchedMillis)
                // e.g. a part of the downloads, which became complete since it was stored, is stored longer
                if (cached.maxAgeMillis < maxAge.inWholeMilliseconds) {
                    file.entries[key] = Entry(cached.fetchedMillis, cached.json, maxAge.inWholeMilliseconds)
                    scheduleWrite(fileName, file)
                }
                return stored
            } catch (e: Exception) {
                logger.warn("Ignoring invalid cached response $key in $fileName: $e")
            }
        }

        val value = loader()
        file.entries[key] = Entry(now, json.encodeToString(serializer, value), maxAge.inWholeMilliseconds)
        scheduleWrite(fileName, file)
        return Stored(value, now)
    }

    /**
     * Marks all stored responses as outdated, they're fetched again when they're requested.
     */
    fun invalidate() {
        invalidatedMillis = currentTimeMillis()
    }

    /**
     * Drops the responses of a file, e.g. of a single plugin.
     */
    fun invalidate(fileName: String) {
        val file = files[fileName]
        if (file == null) {
            store.delete(fileName)
            return
        }
        // a running write must not store the dropped responses again after the file was deleted
        synchronized(file) {
            file.entries.clear()
            store.delete(fileName)
        }
    }

    /**
     * Writes pending changes immediately, e.g. when the application stops, and in tests.
     */
    internal fun flush() {
        // unchanged files aren't written again, e.g. when the application stops
        files.forEach { (name, file) ->
            if (file.isWriteScheduled.getAndSet(false)) {
                write(name, file)
            }
        }
    }

    private fun scheduleWrite(fileName: String, file: FileEntries) {
        if (file.isWriteScheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(writeDelay)
                file.isWriteScheduled.set(false)
                write(fileName, file)
            }
        }
    }

    private fun write(fileName: String, file: FileEntries) {
        // concurrent writes must not replace newer data with older data
        synchronized(file) {
            val now = currentTimeMillis()
            val entries = file.entries
                .filter { (_, entry) -> !entry.isOutdated(now) }
                .map { (key, entry) -> CachedResponse(key, entry.fetchedMillis, entry.json, entry.maxAgeMillis) }
            store.write(fileName, DATA_VERSION, CachedResponses.serializer(), CachedResponses(entries))
        }
    }

    private fun readStored(fileName: String): FileEntries {
        val entries = ConcurrentHashMap<String, Entry>()
        store.read(fileName, DATA_VERSION, CachedResponses.serializer())?.entries?.forEach { entry ->
            entries[entry.key] = Entry(entry.fetchedMillis, entry.json, entry.maxAgeMillis.takeIf { it > 0 } ?: maxAge.inWholeMilliseconds)
        }
        return FileEntries(entries)
    }

    private companion object {
        private const val DATA_VERSION = 1

        private val logger = LoggerFactory.getLogger(PersistentResponseCache::class.java)

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
