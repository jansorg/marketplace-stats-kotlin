/*
 * Copyright (c) 2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PriceInfoByPeriodTest {
    @Test
    fun deserializer() {
        val json = """
        {
            "shopBuyUrl": "https://www.jetbrains.com/shop/buy",
            "shopQuoteUrl": "https://www.jetbrains.com/shop/quote",
            "currency": {
                "iso": "EUR",
                "symbol": "€",
                "prefixSymbol": true
            },
            "pluginInfo": {
                "personal": {
                    "perpetual": {
                        "price": "49.00",
                        "priceTaxed": "58.31",
                        "newShopCode": "P:N:MYLIFETIME"
                    }
                },
                "commercial": {
                    "perpetual": {
                        "price": "99.00",
                        "priceTaxed": "117.81",
                        "newShopCode": "C:N:MYLIFETIME"
                    }
                }
            }
        }
        """.trimIndent()

        val info = Json.decodeFromString<PluginPriceInfo>(json)
        assertEquals(99.0, info.prices.commercial.perpetual?.price?.toDouble())
    }
}