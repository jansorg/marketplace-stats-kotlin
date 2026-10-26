/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.data.PluginDataVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class VersionedCacheTest {
    @Test
    suspend fun valuesAreCalculatedAgainForNewData() {
        val cache = VersionedCache<String, String>()
        val calculations = AtomicInteger()
        val sales = listOf("sale")
        suspend fun get(key: String, version: PluginDataVersion) = cache.get(key, version) { "$key-${calculations.incrementAndGet()}" }

        assertEquals("page-1", get("page", PluginDataVersion(listOf(sales))))
        // the same data
        assertEquals("page-1", get("page", PluginDataVersion(listOf(sales))))
        // another key
        assertEquals("other-2", get("other", PluginDataVersion(listOf(sales))))
        // new data, e.g. after a refresh
        assertEquals("page-3", get("page", PluginDataVersion(listOf(sales + "new sale"))))
        assertEquals("page-3", get("page", PluginDataVersion(listOf(sales + "new sale"))))
        assertEquals(3, calculations.get())
    }
}
