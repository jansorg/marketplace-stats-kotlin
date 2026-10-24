/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.YearMonthDay
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaidPluginModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun releaseDates() {
        val dates = json.decodeFromString<List<PluginReleaseDate>>("""[{"date": [2025, 3, 14], "version": "1.2.0"}]""")
        assertEquals(listOf(PluginReleaseDate("1.2.0", YearMonthDay(2025, 3, 14))), dates)
    }

    @Test
    fun offers() {
        val offers = json.decodeFromString<List<PluginOffer>>(
            """
            [
              {"id": 1, "name": "Discount", "startDate": [2025, 1, 1], "billingPeriod": ["Annual", "Monthly"], "percent": 25, "pluginId": 12345},
              {"id": 2, "name": "Free months", "startDate": [2025, 2, 1], "billingPeriod": ["Annual", "Weekly"], "freePeriodInMonths": 3, "pluginId": 12345}
            ]
            """.trimIndent()
        )
        assertEquals(25, offers[0].discountPercent)
        assertNull(offers[0].freePeriodInMonths)
        assertEquals(listOf(LicensePeriod.Annual, LicensePeriod.Monthly), offers[0].licensePeriods)
        assertEquals(3, offers[1].freePeriodInMonths)
        // unknown periods don't break the client
        assertEquals(listOf(LicensePeriod.Annual), offers[1].licensePeriods)
    }

    @Test
    fun coupons() {
        val coupons = json.decodeFromString<List<PluginOfferCoupon>>("""[{"code": "ABC-123", "active": true, "sent": false}]""")
        assertTrue(coupons.single().isActive)
        assertFalse(coupons.single().isSent)
    }

    @Test
    fun monetization() {
        val monetization = json.decodeFromString<PluginMonetization>(
            """{"productCode": "PPLUGINID", "pricingModel": "FREEMIUM", "checkboxes": ["OBFUSCATE", "NEW_REQUIREMENT"]}"""
        )
        assertEquals(PluginPricingModel.Freemium, monetization.pricingModelValue)
        assertEquals(listOf("OBFUSCATE", "NEW_REQUIREMENT"), monetization.confirmedRequirements)
        assertNull(json.decodeFromString<PluginMonetization>("""{"productCode": "P", "pricingModel": "NEW"}""").pricingModelValue)
    }
}
