/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.model.PluginSale
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PluginSaleTest {
    @Test
    fun deserializer() {
        val json = """
        {
            "ref": "my-ref",
            "date": [
                2024,
                6,
                12
            ],
            "amount": 3.90,
            "amountUSD": 4.19,
            "currency": "EUR",
            "period": "Monthly",
            "customer": {
                "code": 42,
                "name": "",
                "country": "Germany",
                "type": "Personal"
            },
            "reseller": null,
            "lineItems": [
                {
                    "type": "RENEW",
                    "licenseIds": [
                        "abcdef"
                    ],
                    "subscriptionDates": {
                        "start": [
                            2024,
                            6,
                            12
                        ],
                        "end": [
                            2024,
                            7,
                            11
                        ]
                    },
                    "amount": 3.90,
                    "amountUsd": 4.19,
                    "discountDescriptions": []
                }
            ]
        }            
        """.trimIndent()

        val sale = Json.decodeFromString<PluginSale>(json)
        assertEquals("EUR 3.9", sale.lineItems[0].amount.toString())
    }

    @Test
    fun `nullable license period`() {
        val json = """
        {
            "ref": "my-ref",
            "date": [
                2024,
                6,
                12
            ],
            "amount": 3.90,
            "amountUSD": 4.19,
            "currency": "EUR",
            "period": null,
            "customer": {
                "code": 42,
                "name": "",
                "country": "Germany",
                "type": "Personal"
            },
            "reseller": null,
            "lineItems": [
                {
                    "type": "NEW",
                    "licenseIds": [
                        "abcdef"
                    ],
                    "subscriptionDates": {
                        "start": [
                            2024,
                            6,
                            12
                        ],
                        "end": [
                            2024,
                            7,
                            11
                        ]
                    },
                    "amount": 3.90,
                    "amountUsd": 4.19,
                    "discountDescriptions": []
                }
            ]
        }            
        """.trimIndent()

        val sale = Json.decodeFromString<PluginSale>(json)
        assertEquals("EUR 3.9", sale.lineItems[0].amount.toString())
        assertEquals(LicensePeriod.Perpetual, sale.licensePeriod)
    }
}