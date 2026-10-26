/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import com.github.benmanes.caffeine.cache.Caffeine
import dev.ja.marketplace.data.PluginDataVersion

/**
 * Values calculated from the data of a plugin, e.g. the tables of a page, which are used again while the data is the
 * same, see [PluginDataVersion]. New data, e.g. after a refresh, calculates the values again.
 *
 * The values are soft references, the garbage collector removes them, before the JVM runs out of memory, and they're
 * calculated again.
 *
 * @param maximumSize The maximum number of keys, e.g. of plugins and parameters of the requests, which may come from
 *  the address of a request.
 */
class VersionedCache<K : Any, V : Any>(maximumSize: Long = 20) {
    private class Entry<V>(val version: PluginDataVersion, val value: V)

    private val entries = Caffeine.newBuilder().maximumSize(maximumSize).softValues().build<K, Entry<V>>()

    /**
     * @return The value of the key, if it was calculated from data of the same [version], otherwise the new value of
     *  [create], which replaces it. Concurrent requests of new data may both calculate the value.
     */
    suspend fun get(key: K, version: PluginDataVersion, create: suspend () -> V): V {
        entries.getIfPresent(key)?.takeIf { it.version.isSameAs(version) }?.let { return it.value }
        return create().also { entries.put(key, Entry(version, it)) }
    }
}
