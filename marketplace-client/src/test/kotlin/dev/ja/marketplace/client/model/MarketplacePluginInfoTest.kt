/*
 * Copyright (c) 2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketplacePluginInfoTest {
    @Test
    fun deserializer() {
        val json = """
        {
            "code": "PPLUGINID",
            "name": "Plugin (Lifetime)",
            "periods": [],
            "individualPrice": 49.00,
            "businessPrice": 99.00,
            "licensing": "PERPETUAL_UNBOUNDED",
            "status": "IN_STOCK",
            "allowResellers": true,
            "link": "https://plugins.jetbrains.com/plugin/12345",
            "trialPeriod": 14
        }
        """.trimIndent()

        val info = Json.decodeFromString<MarketplacePluginInfo>(json)
        assertTrue(info.licensePeriods.isEmpty())
    }
}