/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.model.PluginRating
import dev.ja.marketplace.client.model.UserInfo
import it.unimi.dsi.fastutil.ints.IntImmutableList
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The collections of [Int] values are decoded as the unmodifiable collections of fastutil. */
class PrimitiveCollectionSerializersTest {
    private val json = Json

    @Test
    fun ratingVotes() {
        val rating = json.decodeFromString<PluginRating>(
            """{"userRating": 0, "meanVotes": 1, "meanRating": 4.0, "votes": {"5": 3, "4": 1, "1": 2}}"""
        )
        assertEquals(mapOf(5 to 3, 4 to 1, 1 to 2), rating.votes)
        assertEquals(2, rating.votes.get(1))
        assertEquals(6, rating.votesCount)
        assertThrows<UnsupportedOperationException> { rating.votes.put(2, 1) }

        assertEquals(rating, json.decodeFromString<PluginRating>(json.encodeToString(rating)))
    }

    @Test
    fun emptyRatingVotes() {
        val rating = json.decodeFromString<PluginRating>("""{"userRating": 0, "meanVotes": 0, "meanRating": 0.0, "votes": {}}""")
        assertEquals(0, rating.votesCount)
    }

    @Test
    fun pluginIdsOfAccount() {
        val user = json.decodeFromString<UserInfo>("""{"id": "user-1", "name": "User", "pluginIds": [3, 2]}""")
        assertInstanceOf(IntImmutableList::class.java, user.pluginIds)
        assertEquals(3, user.pluginIds.getInt(0))
        assertThrows<UnsupportedOperationException> { user.pluginIds.add(1) }
        assertEquals("""{"id":"user-1","name":"User","pluginIds":[3,2]}""", json.encodeToString(user))
    }
}
