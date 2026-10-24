/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

@file:OptIn(ExperimentalSerializationApi::class)

package dev.ja.marketplace.client.persistentCache

import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.model.PluginPriceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
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
 * Stored prices of a plugin, by country.
 * The prices are stored as JSON of the API model, which is small and isn't affected by changes of the model's field order.
 */
@Serializable
internal class CachedPrices(
    @ProtoNumber(1) val entries: List<CachedPriceEntry> = emptyList(),
)

@Serializable
internal class CachedPriceEntry(
    @ProtoNumber(1) val countryIsoCode: String,
    @ProtoNumber(2) val fetchedMillis: Long,
    @ProtoNumber(3) val priceInfoJson: String,
)

/**
 * Persistent cache of the prices of plugins in the countries. The pricing of a plugin is loaded with a request per country,
 * which is slow for all countries. Prices rarely change, cached prices are used until they're older than [maxAge].
 * Changes are written to disk after [writeDelay], to store the prices of many countries at once.
 */
internal class PersistentPriceCache(
    private val store: PersistentCacheStore,
    private val scope: CoroutineScope,
    private val maxAge: Duration = 7.days,
    private val writeDelay: Duration = 2.seconds,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val fetchedMillis: Long, val priceInfo: PluginPriceInfo)

    private class PluginPrices(val entries: ConcurrentHashMap<String, Entry>) {
        val isWriteScheduled = AtomicBoolean(false)
    }

    private val plugins = ConcurrentHashMap<PluginId, PluginPrices>()

    // prices fetched before this time are outdated, set by invalidate
    @Volatile
    private var invalidatedMillis = 0L

    suspend fun get(plugin: PluginId, countryIsoCode: String, loader: suspend () -> PluginPriceInfo): PluginPriceInfo {
        val prices = plugins[plugin] ?: withContext(Dispatchers.IO) { plugins.computeIfAbsent(plugin, ::readStored) }
        val now = currentTimeMillis()
        val cached = prices.entries[countryIsoCode]
        if (cached != null && cached.fetchedMillis >= invalidatedMillis && now - cached.fetchedMillis < maxAge.inWholeMilliseconds) {
            return cached.priceInfo
        }

        val priceInfo = loader()
        prices.entries[countryIsoCode] = Entry(now, priceInfo)
        scheduleWrite(plugin, prices)
        return priceInfo
    }

    /**
     * Marks all cached prices as outdated, they're fetched again when they're requested.
     */
    fun invalidate() {
        invalidatedMillis = currentTimeMillis()
    }

    /**
     * Writes pending changes immediately, for tests.
     */
    internal fun flush() {
        plugins.forEach { (plugin, prices) -> write(plugin, prices) }
    }

    private fun scheduleWrite(plugin: PluginId, prices: PluginPrices) {
        if (prices.isWriteScheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(writeDelay)
                prices.isWriteScheduled.set(false)
                write(plugin, prices)
            }
        }
    }

    private fun write(plugin: PluginId, prices: PluginPrices) {
        // concurrent writes must not replace newer data with older data
        synchronized(prices) {
            val entries = prices.entries.map { (country, entry) ->
                CachedPriceEntry(country, entry.fetchedMillis, json.encodeToString(PluginPriceInfo.serializer(), entry.priceInfo))
            }
            store.write(fileName(plugin), DATA_VERSION, CachedPrices.serializer(), CachedPrices(entries))
        }
    }

    private fun readStored(plugin: PluginId): PluginPrices {
        val entries = ConcurrentHashMap<String, Entry>()
        val stored = store.read(fileName(plugin), DATA_VERSION, CachedPrices.serializer())
        stored?.entries?.forEach { entry ->
            try {
                entries[entry.countryIsoCode] = Entry(entry.fetchedMillis, json.decodeFromString(PluginPriceInfo.serializer(), entry.priceInfoJson))
            } catch (e: Exception) {
                logger.warn("Ignoring invalid cached price of plugin $plugin in ${entry.countryIsoCode}: $e")
            }
        }
        if (stored != null) {
            logger.info("Loaded ${entries.size} cached prices from ${store.directory}")
        }
        return PluginPrices(entries)
    }

    private companion object {
        private const val DATA_VERSION = 1

        private val logger = LoggerFactory.getLogger(PersistentPriceCache::class.java)

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun fileName(plugin: PluginId): String = "plugin-$plugin-prices"
    }
}
