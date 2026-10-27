/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.services.ProductRelease
import dev.ja.marketplace.services.ProductReleaseType
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Values of enums of the API, which the Marketplace or the JetBrains services may extend, are decoded as `Unknown`
 * and don't fail the response.
 */
class UnknownApiValuesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun licensingType() {
        val info = json.decodeFromString<MarketplacePluginInfo>(
            """{"code": "PCODE", "name": "Plugin", "periods": [], "licensing": "NEW_LICENSING"}"""
        )
        assertEquals(LicensingType.Unknown, info.licensingType)
        assertNull(info.offeredLicensePeriods)

        val known = json.decodeFromString<MarketplacePluginInfo>(
            """{"code": "PCODE", "name": "Plugin", "periods": [], "licensing": "SUBSCRIPTION_FALLBACK"}"""
        )
        assertEquals(LicensingType.SubscriptionWithFallback, known.licensingType)
        assertEquals(""""SUBSCRIPTION_FALLBACK"""", json.encodeToString(LicensingType.SubscriptionWithFallback))
    }

    @Test
    fun marketplaceProgram() {
        val programs = json.decodeFromString<List<MarketplaceProgram>>("""["STUDENT", "NEW_PROGRAM", "START_UP"]""")
        assertEquals(listOf(MarketplaceProgram.Student, MarketplaceProgram.Unknown, MarketplaceProgram.Startups), programs)
    }

    @Test
    fun productReleaseType() {
        val releases = json.decodeFromString<List<ProductRelease>>(
            """
            [
              {"date": "2026-04-15", "type": "release", "majorVersion": "2026.1", "version": "2026.1"},
              {"date": "2026-03-15", "type": "nightly", "majorVersion": "2026.1", "version": "2026.1-nightly"}
            ]
            """
        )
        assertEquals(listOf(ProductReleaseType.Release, ProductReleaseType.Unknown), releases.map { it.type })
        assertEquals(""""rc"""", json.encodeToString(ProductReleaseType.ReleaseCandidate))
    }
}
