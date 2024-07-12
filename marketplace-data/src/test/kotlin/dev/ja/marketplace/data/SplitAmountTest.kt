/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SplitAmountTest {
    @Test
    fun splitAmount() {
        val amountEUR = FastMoney.of(1.1, "EUR")
        val amountUSD = FastMoney.of(1.0, "USD")
        SplitAmount.split(amountEUR, amountUSD, listOf("a", "b", "c")) { splitEur, splitUsd, item ->
            if (item == "c") {
                assertEquals(FastMoney.of(0.38, "EUR"), splitEur)
                assertEquals(FastMoney.of(0.34, "USD"), splitUsd)
            } else {
                assertEquals(FastMoney.of(0.36, "EUR"), splitEur)
                assertEquals(FastMoney.of(0.33, "USD"), splitUsd)
            }
        }
    }

    @Test
    fun splitAmount2() {
        val amountEUR = FastMoney.of(110, "EUR")
        val amountUSD = FastMoney.of(100, "USD")
        SplitAmount.split(amountEUR, amountUSD, listOf("a", "b", "c")) { splitEur, splitUsd, item ->
            if (item == "c") {
                assertEquals(FastMoney.of(36.68, "EUR"), splitEur)
                assertEquals(FastMoney.of(33.34, "USD"), splitUsd)
            } else {
                assertEquals(FastMoney.of(36.66, "EUR"), splitEur)
                assertEquals(FastMoney.of(33.33, "USD"), splitUsd)
            }
        }
    }

    @Test
    fun splitNoRemainder() {
        val amountEUR = FastMoney.of(210, "EUR")
        val amountUSD = FastMoney.of(150, "USD")
        SplitAmount.split(amountEUR, amountUSD, listOf("a", "b", "c")) { splitEur, splitUsd, _ ->
            assertEquals(FastMoney.of(70, "EUR"), splitEur)
            assertEquals(FastMoney.of(50, "USD"), splitUsd)
        }
    }
}