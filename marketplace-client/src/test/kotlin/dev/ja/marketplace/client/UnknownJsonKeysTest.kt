/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.model.GroupedDownloadResponse
import dev.ja.marketplace.client.model.PluginReviewComment
import dev.ja.marketplace.client.model.PluginSale
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnknownJsonKeysTest {
    @Test
    fun knownPropertiesOfNestedObjectsAndLists() {
        val json = Json.parseToJsonElement(
            """
            [{
                "id": 1, "cdate": "1700000000000", "comment": "text", "rating": 5, "repliesCount": 0,
                "plugin": {"id": 2, "name": "Plugin", "link": "/plugin/2"},
                "votes": {"positive": 1, "negative": 0, "userVerdict": 0},
                "subRating": {"PERFORMANCE": 4}
            }]
            """
        )
        assertEquals(emptySet<String>(), UnknownJsonKeys.find(json, ListSerializer(PluginReviewComment.serializer()).descriptor))
    }

    @Test
    fun unknownPropertiesWithTheirPaths() {
        val json = Json.parseToJsonElement(
            """
            [{
                "id": 1, "comment": "text", "rating": 5, "repliesCount": 0, "newProperty": true,
                "plugin": {"id": 2, "name": "Plugin", "newNestedProperty": {"ignored": 1}},
                "votes": {"positive": 1, "negative": 0, "newVoteProperty": 1}
            }]
            """
        )
        assertEquals(
            setOf("[].newProperty", "[].plugin.newNestedProperty", "[].votes.newVoteProperty"),
            UnknownJsonKeys.find(json, ListSerializer(PluginReviewComment.serializer()).descriptor),
        )
    }

    @Test
    fun customSerializerAndLists() {
        // the sales use a custom serializer with a descriptor of their properties
        val sales = Json.parseToJsonElement(
            """
            [{
                "ref": "R-1", "date": "2025-01-01", "amount": 10, "currency": "USD", "amountUSD": 10, "period": "Annual",
                "customer": {"code": 1, "name": "Customer", "country": "Germany", "type": "Individual"},
                "lineItems": [{"type": "NEW", "licenseIds": ["L-1"], "amount": 10, "amountUsd": 10, "discountDescriptions": [], "newItemProperty": 1}],
                "newSaleProperty": "x"
            }]
            """
        )
        assertEquals(
            setOf("[].lineItems[].newItemProperty", "[].newSaleProperty"),
            UnknownJsonKeys.find(sales, ListSerializer(PluginSale.serializer()).descriptor),
        )

        val downloads = Json.parseToJsonElement(
            """{"measure": "downloads", "dim1": "month", "dim2": "product_code", "filters": [], "data": [{"dimension": "IU", "serie": [{"name": "2025-01", "value": 1, "unknown": 2}]}]}"""
        )
        assertEquals(setOf("data[].serie[].unknown"), UnknownJsonKeys.find(downloads, GroupedDownloadResponse.serializer().descriptor))
    }
}
