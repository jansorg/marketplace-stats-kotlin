/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.persistentCache

import dev.ja.marketplace.client.currency.CurrencyInfo
import dev.ja.marketplace.client.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.io.path.exists

class PersistentPriceCacheTest {
    @TempDir
    lateinit var tempDir: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var nowMillis = 1_000_000_000_000L
    private val loads = AtomicInteger()

    @AfterEach
    fun cancelScope() {
        scope.cancel()
    }

    private fun price(amount: String) = PluginPriceInfo(
        "https://example.com/buy",
        "https://example.com/quote",
        CurrencyInfo("EUR", "€", false),
        PluginPriceInfoByType(
            PriceInfoByPeriod(annual = PriceInfoTypeData(PriceInfoData(BigDecimal(amount), BigDecimal("119.0"), "code")), monthly = null),
            PriceInfoByPeriod(perpetual = PriceInfoData(BigDecimal(amount), null, "code")),
        )
    )

    private fun createCache() = PersistentPriceCache(PersistentCacheStore(tempDir), scope, currentTimeMillis = { nowMillis })

    private suspend fun PersistentPriceCache.load(country: String, value: PluginPriceInfo?): PluginPriceInfo? {
        return get(1, country) {
            loads.incrementAndGet()
            value
        }.value
    }

    @Test
    suspend fun pricesAreStored() {
        val cache = createCache()
        assertEquals(price("100.0"), cache.load("DE", price("100.0")))
        assertEquals(price("100.0"), cache.load("DE", price("200.0")))
        assertEquals(1, loads.get())
        cache.flush()

        val restarted = createCache()
        assertEquals(price("100.0"), restarted.load("DE", price("200.0")))
        assertEquals(price("300.0"), restarted.load("FR", price("300.0")))
        assertEquals(2, loads.get())
    }

    @Test
    suspend fun countriesWithoutPricesAreStored() {
        val cache = createCache()
        assertNull(cache.load("AT", null))
        assertNull(cache.load("AT", price("100.0")))
        assertEquals(1, loads.get())
        cache.flush()

        // also after a restart, the country isn't requested again
        val restarted = createCache()
        assertNull(restarted.load("AT", price("100.0")))
        assertEquals(1, loads.get())
    }

    @Test
    suspend fun outdatedPricesAreLoaded() {
        val cache = createCache()
        cache.load("DE", price("100.0"))
        cache.flush()

        nowMillis += 8.days.inWholeMilliseconds
        assertEquals(price("200.0"), createCache().load("DE", price("200.0")))
        assertEquals(2, loads.get())
    }

    @Test
    suspend fun invalidatedPricesAreLoaded() {
        val cache = createCache()
        cache.load("DE", price("100.0"))
        nowMillis += 1.hours.inWholeMilliseconds
        cache.invalidate()

        assertEquals(price("200.0"), cache.load("DE", price("200.0")))
        assertEquals(2, loads.get())
    }

    @Test
    suspend fun pricesAreWrittenAfterDelay() {
        val store = PersistentCacheStore(tempDir)
        val cache = PersistentPriceCache(store, scope, writeDelay = 10.milliseconds)
        cache.load("DE", price("100.0"))

        val file = store.file("plugin-1-prices")
        repeat(200) {
            if (!file.exists()) delay(10)
        }
        assertEquals(price("100.0"), createCache().load("DE", price("200.0")))
        assertEquals(1, loads.get())
    }
}
