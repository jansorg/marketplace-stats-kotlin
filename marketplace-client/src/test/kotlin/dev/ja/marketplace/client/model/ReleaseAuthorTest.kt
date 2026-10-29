/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The author of a release is kept as the ID of the account. The API and older stored data have the account with its
 * name and link, which are read as the ID. The ID is written as `authorId`.
 */
class ReleaseAuthorTest {
    // without ignoreUnknownKeys, the name and the link of the account must not be unknown properties
    private val strictJson = Json

    private val releaseJson = """"id": 3, "pluginId": 2, "link": "/plugin/2/3", "version": "1.0", "approve": true, "listed": true,
        "hidden": false, "cdate": "1700000000000", "file": "plugin.zip", "size": 100, "channel": "", "downloads": 5"""

    @Test
    fun releaseWithAuthorOfTheApiOrOlderData() {
        val release = strictJson.decodeFromString<PluginReleaseInfo>(
            """{$releaseJson, "author": {"id": "user-1", "name": "User", "link": "/author/user-1", "hubLogin": "user", "icon": "/icon.png", "isJetBrains": false}}"""
        )
        assertEquals("user-1", release.authorId)

        val encoded = strictJson.parseToJsonElement(strictJson.encodeToString(release)).jsonObject
        assertEquals("\"user-1\"", encoded["authorId"].toString())
        assertFalse("author" in encoded)
        assertEquals(release, strictJson.decodeFromString<PluginReleaseInfo>(encoded.toString()))
    }

    @Test
    fun releaseWithAuthorId() {
        assertEquals("user-1", strictJson.decodeFromString<PluginReleaseInfo>("""{$releaseJson, "authorId": "user-1"}""").authorId)
    }

    @Test
    fun releaseWithoutAuthor() {
        for (value in listOf("""{$releaseJson}""", """{$releaseJson, "author": null}""", """{$releaseJson, "authorId": null}""")) {
            assertNull(strictJson.decodeFromString<PluginReleaseInfo>(value).authorId, value)
        }
    }

    @Test
    fun storedReleasesInBothFormats() {
        // e.g. the pages of the releases in the persistent cache
        val serializer = ListSerializer(PluginReleaseInfo.serializer())
        val older = """[{$releaseJson, "author": {"id": "user-1", "name": "User"}}, {$releaseJson, "authorId": "user-2"}]"""
        val releases = strictJson.decodeFromString(serializer, older)
        assertEquals(listOf("user-1", "user-2"), releases.map(PluginReleaseInfo::authorId))
        assertEquals(releases, strictJson.decodeFromString(serializer, strictJson.encodeToString(serializer, releases)))
    }

    @Test
    fun authorWithoutIdFails() {
        assertThrows<SerializationException> {
            strictJson.decodeFromString<PluginReleaseInfo>("""{$releaseJson, "author": {"name": "User"}}""")
        }
        assertThrows<SerializationException> {
            strictJson.decodeFromString<PluginReleaseInfo>("""{$releaseJson, "author": ["user-1"]}""")
        }
    }

    @Test
    fun lenientJsonReadsOlderData() {
        val lenientJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val release = lenientJson.decodeFromString<PluginReleaseInfo>("""{$releaseJson, "author": {"id": "user-1", "name": "User"}}""")
        assertEquals("user-1", release.authorId)
    }
}
