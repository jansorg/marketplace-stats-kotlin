/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.engine.mock.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class PluginAutoRefreshTest {
    private var nowMillis = 0L
    private val client = CaffeineCacheMarketplaceClient(httpClientBuilder = mockHttpClientBuilder(MockEngine { error("unexpected request") }))

    @Test
    suspend fun requestedPluginsAreRefreshedUntilTheyAreIdle() {
        val refresh = PluginAutoRefresh(client, CachingStrategy(autoRefreshInterval = 30.minutes), currentTimeMillis = { nowMillis })
        refresh.onRequest(1)
        nowMillis += 12.hours.inWholeMilliseconds
        refresh.onRequest(2)
        refresh.refreshRequestedPlugins()
        assertEquals(setOf(1, 2), refresh.refreshedPlugins)

        // a day after its last request, a plugin isn't refreshed anymore
        nowMillis += 13.hours.inWholeMilliseconds
        refresh.refreshRequestedPlugins()
        assertEquals(setOf(2), refresh.refreshedPlugins)

        refresh.onRequest(1)
        assertEquals(setOf(1, 2), refresh.refreshedPlugins)
    }

    @Test
    fun noPluginsWithoutTheAutomaticRefresh() {
        val refresh = PluginAutoRefresh(client, CachingStrategy(), currentTimeMillis = { nowMillis })
        refresh.onRequest(1)
        assertEquals(emptySet<PluginId>(), refresh.refreshedPlugins)
        assertEquals(null, refresh.start(CoroutineScope(Dispatchers.Default)))
    }
}
