/*
 * Copyright (c) 2025-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
        assertEquals(setOf(LicensePeriod.Perpetual), info.offeredLicensePeriods)
    }

    @Test
    fun offeredLicensePeriods() {
        fun info(licensing: LicensingType?, vararg periods: LicensePeriod): MarketplacePluginInfo {
            return MarketplacePluginInfo("PCODE", "Plugin", periods.toList(), licensingType = licensing)
        }

        val subscription = setOf(LicensePeriod.Annual, LicensePeriod.Monthly)
        assertEquals(subscription, info(LicensingType.Subscription).offeredLicensePeriods)
        assertEquals(subscription, info(LicensingType.SubscriptionWithFallback).offeredLicensePeriods)
        assertEquals(setOf(LicensePeriod.Annual), info(LicensingType.Subscription, LicensePeriod.Annual).offeredLicensePeriods)
        assertEquals(setOf(LicensePeriod.Perpetual), info(LicensingType.Perpetual).offeredLicensePeriods)
        assertNull(info(null).offeredLicensePeriods)

        // all periods, if the Marketplace supports subscriptions and perpetual licenses of the same plugin
        assertEquals(
            (LicensePeriod.entries - LicensePeriod.Unknown).toSet(),
            info(LicensingType.Perpetual, LicensePeriod.Annual, LicensePeriod.Monthly).offeredLicensePeriods
        )
        assertEquals(
            (LicensePeriod.entries - LicensePeriod.Unknown).toSet(),
            info(LicensingType.Subscription, *(LicensePeriod.entries - LicensePeriod.Unknown).toTypedArray()).offeredLicensePeriods
        )
    }
}