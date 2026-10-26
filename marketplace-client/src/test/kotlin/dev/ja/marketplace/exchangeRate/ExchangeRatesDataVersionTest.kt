/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.exchangeRate

import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ExchangeRatesDataVersionTest {
    private fun table() = EcbRateTable.parseCsv(
        """
        Date,USD,JPY,
        2024-04-12,1.0652,163.16,
        2024-04-11,1.0729,164.29,
        """.trimIndent().byteInputStream()
    )

    @Test
    fun versionChangesWhenTheRatesAreUpdated() {
        val downloads = AtomicInteger()
        val data = EcbExchangeRateData(downloader = { downloads.incrementAndGet(); table() })
        val rates = ExchangeRates("EUR", data)

        val first = rates.dataVersion
        assertSame(first, rates.dataVersion)

        // outdated rates are used, until the update in the background is done
        data.invalidate()
        assertSame(first, rates.dataVersion)
        data.awaitBackgroundRefresh()
        assertNotSame(first, rates.dataVersion)
    }

    @Test
    fun amountsInUsdDontLoadTheRates() {
        val data = EcbExchangeRateData(downloader = { throw IllegalStateException("must not download") })
        assertNull(ExchangeRates("USD", data).dataVersion)
    }
}
