/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.persistentCache.PersistentCacheStore
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The channels, the releases and the dependencies of releases are stored, a restart doesn't request them again.
 */
class StoredReleasesTest {
    @TempDir
    lateinit var tempDir: Path

    /** The requested paths. */
    private val requests = CopyOnWriteArrayList<String>()

    private fun createClient(): CaffeineCacheMarketplaceClient {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requests += path
            val body = when {
                path.endsWith("/channels") -> """["", "eap"]"""
                // the API returns the plugin of a dependency with its name and link
                path.endsWith("/dependencies") -> """[{"name": "com.intellij.modules.platform"}, {"name": "(optional) Docker", "plugin": {"id": 7724, "name": "Docker", "link": "/plugin/7724-docker"}}]"""
                else -> error("Unexpected request $path")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val store = PersistentCacheStore(tempDir).accountStore(Marketplace.HOSTNAME, "key")
        return CaffeineCacheMarketplaceClient(httpClientBuilder = mockHttpClientBuilder(engine), persistentCache = store)
    }

    @Test
    suspend fun theChannelsAndTheDependenciesAreStored() {
        createClient().use { client ->
            assertEquals(listOf("", "eap"), client.channels(1))
            assertEquals(listOf("com.intellij.modules.platform", "Docker"), client.pluginReleaseDependencies(10).map { it.name })
        }
        assertEquals(2, requests.size)

        // after a restart, the stored responses are used
        requests.clear()
        createClient().use { client ->
            assertEquals(listOf("", "eap"), client.channels(1))
            assertEquals(listOf("com.intellij.modules.platform", "Docker"), client.pluginReleaseDependencies(10).map { it.name })
            // the stored dependencies keep the ID of the plugin
            assertEquals(listOf(null, 7724), client.pluginReleaseDependencies(10).map { it.pluginId })
        }
        assertEquals(emptyList<String>(), requests)
    }

    @Test
    suspend fun theReleasesOfADroppedPluginAreRequestedAgain() {
        createClient().use { client ->
            client.channels(1)
            client.invalidatePlugin(1)
        }
        requests.clear()
        createClient().use { client ->
            assertEquals(listOf("", "eap"), client.channels(1))
        }
        assertEquals(1, requests.size)
    }
}
