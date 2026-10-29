/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.PluginId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The plugin of a dependency and of a review is kept as its ID. The API and older stored data have the plugin
 * with its name and link, which are read as the ID. The ID is written as `pluginId`.
 */
class PluginIdOrInfoTest {
    // without ignoreUnknownKeys, the name and the link of the plugin must not be unknown properties
    private val strictJson = Json

    private val reviewJson = """"id": 1, "cdate": "1700000000000", "comment": "text", "rating": 5, "repliesCount": 0"""

    @Test
    fun dependencyWithPluginOfTheApiOrOlderData() {
        val dependency = strictJson.decodeFromString<PluginDependency>(
            """{"name": "(optional) Docker", "plugin": {"id": 7724, "name": "Docker", "link": "/plugin/7724-docker"}}"""
        )
        assertEquals(PluginDependency("(optional) Docker", 7724), dependency)
        assertEquals("""{"name":"(optional) Docker","pluginId":7724}""", strictJson.encodeToString(dependency))
    }

    @Test
    fun dependencyWithPluginId() {
        val dependency = strictJson.decodeFromString<PluginDependency>("""{"name": "Docker", "pluginId": 7724}""")
        assertEquals(PluginDependency("Docker", 7724), dependency)
    }

    @Test
    fun dependencyWithoutPlugin() {
        // e.g. a module of the platform
        for (value in listOf("""{"name": "com.intellij.modules.platform"}""", """{"name": "com.intellij.modules.platform", "plugin": null}""", """{"name": "com.intellij.modules.platform", "pluginId": null}""")) {
            assertNull(strictJson.decodeFromString<PluginDependency>(value).pluginId, value)
        }
        assertEquals("""{"name":"com.intellij.modules.platform"}""", strictJson.encodeToString(PluginDependency("com.intellij.modules.platform")))
        val withDefaults = Json { encodeDefaults = true }
        val encoded = withDefaults.encodeToString(PluginDependency("com.intellij.modules.platform"))
        assertEquals("""{"name":"com.intellij.modules.platform","pluginId":null}""", encoded)
        assertEquals(PluginDependency("com.intellij.modules.platform"), strictJson.decodeFromString<PluginDependency>(encoded))
    }

    @Test
    fun storedDependenciesOfReleasesInBothFormats() {
        // e.g. the dependencies of the releases of a snapshot of the analyzer
        val serializer = MapSerializer(PluginId.serializer(), ListSerializer(PluginDependency.serializer()))
        val older = """{"1": [{"name": "Docker", "plugin": {"id": 7724, "name": "Docker"}}, {"name": "com.intellij.modules.platform"}], "2": []}"""
        val dependencies = strictJson.decodeFromString(serializer, older)
        val newer = strictJson.encodeToString(serializer, dependencies)

        assertEquals("""{"1":[{"name":"Docker","pluginId":7724},{"name":"com.intellij.modules.platform"}],"2":[]}""", newer)
        assertEquals(dependencies, strictJson.decodeFromString(serializer, newer))
    }

    @Test
    fun reviewWithPluginOfTheApiOrOlderData() {
        val review = strictJson.decodeFromString<PluginReviewComment>("""{$reviewJson, "plugin": {"id": 2, "name": "Plugin", "link": "/plugin/2"}}""")
        assertEquals(2, review.pluginId)

        val encoded = strictJson.parseToJsonElement(strictJson.encodeToString(review)).jsonObject
        assertEquals("2", encoded["pluginId"].toString())
        assertFalse("plugin" in encoded)
        assertEquals(review, strictJson.decodeFromString<PluginReviewComment>(encoded.toString()))
    }

    @Test
    fun reviewWithPluginId() {
        val review = strictJson.decodeFromString<PluginReviewComment>("""{$reviewJson, "pluginId": 2}""")
        assertEquals(2, review.pluginId)
    }

    @Test
    fun reviewWithoutPluginFails() {
        assertThrows<SerializationException> { strictJson.decodeFromString<PluginReviewComment>("""{$reviewJson}""") }
    }

    @Test
    fun pluginWithoutIdFails() {
        assertThrows<SerializationException> {
            strictJson.decodeFromString<PluginDependency>("""{"name": "Docker", "plugin": {"name": "Docker"}}""")
        }
        assertThrows<SerializationException> {
            strictJson.decodeFromString<PluginDependency>("""{"name": "Docker", "plugin": ["7724"]}""")
        }
    }

    @Test
    fun lenientSnapshotJsonReadsOlderData() {
        // the analyzer reads its snapshots with ignoreUnknownKeys and coerceInputValues
        val snapshotJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val review = snapshotJson.decodeFromString<PluginReviewComment>("""{$reviewJson, "plugin": {"id": 2, "name": "Plugin"}}""")
        assertEquals(2, review.pluginId)
        val dependency = snapshotJson.decodeFromString<PluginDependency>("""{"name": "Docker", "plugin": {"id": 7724, "name": "Docker"}}""")
        assertEquals(7724, dependency.pluginId)
    }
}
