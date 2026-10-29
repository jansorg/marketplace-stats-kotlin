/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.YearMonthDay
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The properties of the responses, which were added to the models, with made-up data.
 */
class ResponsePropertiesTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun reviewWithResolveStateAndSubRatings() {
        val review = json.decodeFromString<PluginReviewComment>(
            """
            {
                "id": 1, "cdate": "1700000000000", "comment": "text", "rating": 2, "repliesCount": 0,
                "plugin": {"id": 2, "name": "Plugin"}, "vendor": false, "markedAsSpam": false,
                "resolveState": "RESOLVED", "isResolveRequested": true, "isDeleted": true,
                "subRating": {"PERFORMANCE": 4, "USER_INTERFACE": 5},
                "ideVersion": "IU-251.1", "updateId": 3, "updateVersion": "1.2.3",
                "votes": {"positive": 1, "negative": 2, "userVerdict": -1}
            }
            """
        )
        assertEquals(2, review.pluginId)
        assertTrue(review.isResolved)
        assertTrue(review.isResolveRequested)
        assertTrue(review.isDeleted)
        assertEquals(mapOf(PerformanceRating to 4, UserInterfaceRating to 5), review.subRating)
        assertEquals("IU-251.1", review.ideVersion)
        assertEquals(3, review.releaseId)
        assertEquals("1.2.3", review.releaseVersion)
        assertEquals(-1, review.votes?.userVerdict)
    }

    @Test
    fun reviewWithoutTheNewProperties() {
        val review = json.decodeFromString<PluginReviewComment>(
            """{"id": 1, "comment": "text", "rating": 5, "repliesCount": 0, "plugin": {"id": 2, "name": "Plugin"}, "vendor": false, "markedAsSpam": false}"""
        )
        assertFalse(review.isResolved)
        assertFalse(review.isDeleted)
        assertNull(review.resolveState)
    }

    @Test
    fun userWithPluginsCommentsAndReleases() {
        val user = json.decodeFromString<UserInfo>(
            """
            {
                "id": "user-1", "name": "User", "email": "user@example.com", "emailBanned": false, "hubLogin": "user",
                "icon": "/icon.png", "link": "/author/user", "showMarketoCheckbox": true, "teamcityServers": [],
                "plugins": [{"id": 2, "name": "Plugin", "link": "/plugin/2"}],
                "comments": [{
                    "id": 1, "cdate": "1700000000000", "comment": "text", "rating": 5, "repliesCount": 0,
                    "plugin": {"id": 2, "name": "Plugin"}, "author": {"id": "user-1", "name": "User"},
                    "resolveState": "INIT", "isResolveRequested": false
                }],
                "updates": [{
                    "id": 3, "pluginId": 2, "link": "/plugin/2/3", "version": "1.0", "approve": true, "listed": true,
                    "hidden": false, "cdate": "1700000000000", "file": "plugin.zip", "size": 100, "channel": "", "downloads": 5
                }]
            }
            """
        )
        assertEquals("user@example.com", user.email)
        assertEquals(listOf(2), user.plugins.map { it.id })
        // the comments of the account have no vendor and spam flags
        assertFalse(user.comments.single().vendor)
        assertEquals(2, user.comments.single().pluginId)
        assertEquals(listOf(3), user.releases.map { it.id })
    }

    @Test
    fun offerEndDateAndPluginProperties() {
        val offer = json.decodeFromString<PluginOffer>(
            """{"id": 1, "name": "Offer", "pluginId": 2, "startDate": [2025, 1, 1], "endDate": [2025, 3, 31], "description": "Spring", "percent": 20}"""
        )
        assertEquals(YearMonthDay(2025, 3, 31), offer.endDate)
        assertEquals("Spring", offer.description)

        val openOffer = json.decodeFromString<PluginOffer>("""{"id": 1, "name": "Offer", "pluginId": 2, "startDate": [2025, 1, 1], "endDate": null}""")
        assertNull(openOffer.endDate)

        val vendor = json.decodeFromString<PluginVendor>(
            """{"type": "organization", "marketplaceVendorId": 7, "showEmail": true, "details": {"address": "Street 1", "city": "City", "state": null, "zip": "12345", "phone": "+1"}}"""
        )
        assertEquals(7, vendor.marketplaceVendorId)
        assertEquals("City", vendor.details?.city)
    }
}
