/*
 * Copyright (c) 2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PriceInfoTypeDataTest {
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
                    "monthly": {
                        "firstYear": {
                            "price": "1.00",
                            "priceTaxed": "1.19",
                            "newShopCode": "P:N:ID:M"
                        }
                    },
                    "annual": {
                        "firstYear": {
                            "price": "10.00",
                            "priceTaxed": "11.90",
                            "newShopCode": "P:N:ID:Y"
                        }
                    }
                },
                "commercial": {
                    "monthly": {
                        "firstYear": {
                            "price": "2.00",
                            "priceTaxed": "2.38",
                            "newShopCode": "C:N:ID:M"
                        }
                    },
                    "annual": {
                        "firstYear": {
                            "price": "20.00",
                            "priceTaxed": "23.80",
                            "newShopCode": "C:N:ID:Y"
                        }
                    }
                }
            }
        }
        """.trimIndent()

        val info = Json.decodeFromString<PluginPriceInfo>(json)
        assertEquals(10.0, info.prices.personal.annual?.firstYear?.price?.toDouble())
        Assertions.assertNull(info.prices.personal.annual?.secondYear)
        Assertions.assertNull(info.prices.personal.annual?.thirdYear)

        assertEquals(20.0, info.prices.commercial.annual?.firstYear?.price?.toDouble())
        Assertions.assertNull(info.prices.commercial.annual?.secondYear)
        Assertions.assertNull(info.prices.commercial.annual?.thirdYear)
    }
}