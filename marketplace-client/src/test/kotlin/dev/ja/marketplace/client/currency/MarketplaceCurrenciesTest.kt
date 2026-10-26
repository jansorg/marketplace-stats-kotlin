/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.currency

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MarketplaceCurrenciesTest {
    @Test
    fun otherCurrenciesAreSupported() {
        assertEquals("EUR", MarketplaceCurrencies.of("EUR").currencyCode)
        // e.g. a currency, which the Marketplace adds later
        assertEquals("CHF", MarketplaceCurrencies.of("CHF").currencyCode)
    }
}
