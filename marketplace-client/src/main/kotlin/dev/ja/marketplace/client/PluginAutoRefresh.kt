/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Refreshes the cached data of the plugins, which were requested, in the background every
 * [CachingStrategy.autoRefreshInterval], so that requests use up-to-date data and don't wait for the Marketplace.
 * Plugins, which weren't requested, aren't refreshed. The refresh of a plugin stops [CachingStrategy.autoRefreshIdleTime]
 * after its last request, see [onRequest].
 */
class PluginAutoRefresh(
    private val client: CaffeineCacheMarketplaceClient,
    private val strategy: CachingStrategy,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    // the time of the last request of each refreshed plugin
    private val lastRequests = ConcurrentHashMap<PluginId, Long>()

    /** The plugins, which are refreshed. */
    val refreshedPlugins: Set<PluginId>
        get() = lastRequests.keys.toSet()

    /**
     * A request needs the data of the plugin, its data is refreshed until [CachingStrategy.autoRefreshIdleTime] after
     * the last request.
     */
    fun onRequest(plugin: PluginId) {
        if (strategy.autoRefreshInterval != null) {
            lastRequests[plugin] = currentTimeMillis()
        }
    }

    /**
     * Starts the refresh in the [scope], every interval after the start of the previous refresh.
     *
     * @return The job of the refresh, or `null` if the automatic refresh is disabled.
     */
    fun start(scope: CoroutineScope): Job? {
        val interval = strategy.autoRefreshInterval ?: return null
        return scope.launch {
            var next = currentTimeMillis() + interval.inWholeMilliseconds
            while (isActive) {
                delay((next - currentTimeMillis()).coerceAtLeast(0).milliseconds)
                val start = currentTimeMillis()
                next = start + interval.inWholeMilliseconds
                refreshRequestedPlugins()
                if (lastRequests.isNotEmpty()) {
                    logger.info("Refreshed the data of ${lastRequests.size} plugins in ${(currentTimeMillis() - start).milliseconds}")
                }
            }
        }
    }

    /**
     * Refreshes the plugins, which were requested within [CachingStrategy.autoRefreshIdleTime], one after the other.
     * The other plugins aren't refreshed anymore.
     */
    internal suspend fun refreshRequestedPlugins() {
        val now = currentTimeMillis()
        lastRequests.entries.removeIf { (_, lastRequest) -> now - lastRequest > strategy.autoRefreshIdleTime.inWholeMilliseconds }
        for (plugin in lastRequests.keys.sorted()) {
            try {
                client.refreshPlugin(plugin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Unable to refresh the data of plugin $plugin", e)
            }
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(PluginAutoRefresh::class.java)
    }
}
