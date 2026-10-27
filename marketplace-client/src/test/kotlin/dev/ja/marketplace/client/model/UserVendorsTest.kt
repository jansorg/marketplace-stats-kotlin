/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UserVendorsTest {
    // the model contains all properties of the response
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun responseOfUserVendors() {
        // a personal vendor and an organization of a response of /api/users/{id}/vendors
        val vendors = json.decodeFromString<List<PluginVendor>>(
            """
            [{"type": "Organization", "id": 1001, "name": "00000000-0000-0000-0000-000000000001", "url": "https://example.com", "link": "/vendor/00000000-0000-0000-0000-000000000001", "publicName": "Jane Doe", "showEmail": false, "countryCode": "DE", "country": "Germany", "isVerified": false, "isTrader": false}, {"type": "Organization", "id": 2002, "name": "Example-Vendor", "url": "https://vendor.example.com/", "link": "/vendor/Example-Vendor", "publicName": "Example Vendor", "email": "support@vendor.example.com", "showEmail": true, "countryCode": "CZ", "country": "Czech Republic", "isVerified": true, "isTrader": true, "description": "Plugins made by an example vendor.", "details": {"city": "Example City", "address": "Example Street 1, 12345", "state": null, "zip": null, "phone": "+00 000 000 000"}, "marketplaceVendorId": 2002}]
            """
        )

        assertEquals(listOf("00000000-0000-0000-0000-000000000001", "Example-Vendor"), vendors.map { it.name })
        assertEquals("Example Vendor", vendors[1].publicName)
        assertEquals("/vendor/Example-Vendor", vendors[1].link)
    }
}
