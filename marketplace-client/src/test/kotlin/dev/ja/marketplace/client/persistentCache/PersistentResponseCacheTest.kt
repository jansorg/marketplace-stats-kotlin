/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.persistentCache

import dev.ja.marketplace.client.model.DownloadDimension
import dev.ja.marketplace.client.model.DownloadFilter
import dev.ja.marketplace.client.model.DownloadFilterType
import dev.ja.marketplace.client.model.DownloadResponse
import dev.ja.marketplace.client.model.DownloadResponseData
import dev.ja.marketplace.client.model.DownloadResponseItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class PersistentResponseCacheTest {
    @TempDir
    lateinit var tempDir: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var nowMillis = 1_000_000_000_000L
    private val loads = AtomicInteger()

    @AfterEach
    fun cancelScope() {
        scope.cancel()
    }

    private fun createCache() = PersistentResponseCache(PersistentCacheStore(tempDir), scope, maxAge = 1.days, currentTimeMillis = { nowMillis })

    private val response = DownloadResponse(
        "downloads-count",
        listOf(DownloadFilter(DownloadFilterType.Plugin, "1")),
        DownloadDimension.Month,
        DownloadResponseData(DownloadDimension.Month, listOf(DownloadResponseItem("2026-01-01", 42))),
    )

    private suspend fun PersistentResponseCache.load(key: String = "monthly", value: DownloadResponse = response): DownloadResponse {
        return get("plugin-1-downloads", key, DownloadResponse.serializer()) {
            loads.incrementAndGet()
            value
        }.value
    }

    @Test
    suspend fun storedResponsesAreUsedAfterARestart() {
        val cache = createCache()
        assertEquals(response, cache.load())
        assertEquals(response, cache.load())
        assertEquals(1, loads.get())
        cache.flush()

        // a new instance reads the file
        assertEquals(response, createCache().load())
        assertEquals(1, loads.get())
    }

    @Test
    suspend fun storedResponsesHaveTheTimeOfTheirFetch() {
        val fetched = nowMillis
        createCache().apply { load() }.flush()

        // e.g. the memory cache keeps the response only for the rest of the day
        nowMillis += 23.hours.inWholeMilliseconds
        val stored = createCache().get("plugin-1-downloads", "monthly", DownloadResponse.serializer()) { error("must not fetch") }
        assertEquals(fetched, stored.fetchedMillis)
    }

    @Test
    suspend fun responsesWithALongerMaximumAgeAreKeptLonger() {
        val cache = createCache()
        cache.get("plugin-1-downloads", "past", DownloadResponse.serializer(), maxAge = 7.days) { loads.incrementAndGet(); response }
        cache.flush()

        // the maximum age is stored with the response, it's kept after a restart and when the file is written again
        nowMillis += 3.days.inWholeMilliseconds
        val restarted = createCache()
        restarted.get("plugin-1-downloads", "past", DownloadResponse.serializer(), maxAge = 7.days) { error("must not fetch") }
        restarted.load("other")
        restarted.flush()
        nowMillis += 3.days.inWholeMilliseconds
        createCache().get("plugin-1-downloads", "past", DownloadResponse.serializer(), maxAge = 7.days) { error("must not fetch") }
        assertEquals(2, loads.get())

        nowMillis += 2.days.inWholeMilliseconds
        createCache().get("plugin-1-downloads", "past", DownloadResponse.serializer(), maxAge = 7.days) { loads.incrementAndGet(); response }
        assertEquals(3, loads.get())
    }

    @Test
    suspend fun responsesAreFetchedAgainAfterADay() {
        val cache = createCache()
        cache.load()
        cache.flush()

        nowMillis += 23.hours.inWholeMilliseconds
        createCache().load()
        assertEquals(1, loads.get())

        nowMillis += 2.hours.inWholeMilliseconds
        val restarted = createCache()
        restarted.load()
        assertEquals(2, loads.get())
        // the new value is stored again
        restarted.load()
        assertEquals(2, loads.get())
    }

    @Test
    suspend fun invalidation() {
        val cache = createCache()
        cache.load()
        nowMillis += 1
        cache.invalidate()
        cache.load()
        assertEquals(2, loads.get())

        cache.flush()
        cache.invalidate("plugin-1-downloads")
        createCache().load()
        assertEquals(3, loads.get())
    }

    @Test
    suspend fun valuesOfOtherTypes() {
        val cache = createCache()
        assertEquals(42L, cache.get("plugin-1-downloads", "total", Long.serializer()) { 42L }.value)
        cache.flush()
        assertEquals(42L, createCache().get("plugin-1-downloads", "total", Long.serializer()) { error("must be stored") }.value)
    }
}
