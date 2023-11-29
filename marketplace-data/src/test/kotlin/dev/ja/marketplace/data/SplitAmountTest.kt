/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.Amount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.RoundingMode

class SplitAmountTest {
    @Test
    fun splitAmount() {
        val amountEUR = Amount(1.1)
        val amountUSD = Amount(1.0)
        SplitAmount.split(amountEUR, amountUSD, listOf("a", "b", "c")) { splitEur, splitUsd, item ->
            if (item == "c") {
                assertEquals(Amount(0.38).setScale(2, RoundingMode.HALF_UP), splitEur)
                assertEquals(Amount(0.34).setScale(2, RoundingMode.HALF_UP), splitUsd)
            } else {
                assertEquals(Amount(0.36).setScale(2, RoundingMode.HALF_UP), splitEur)
                assertEquals(Amount(0.33).setScale(2, RoundingMode.HALF_UP), splitUsd)
            }
        }
    }

    @Test
    fun splitAmount2() {
        val amountEUR = Amount(110)
        val amountUSD = Amount(100)
        SplitAmount.split(amountEUR, amountUSD, listOf("a", "b", "c")) { splitEur, splitUsd, item ->
            if (item == "c") {
                assertEquals(Amount(36.68).setScale(2, RoundingMode.HALF_UP), splitEur)
                assertEquals(Amount(33.34).setScale(2, RoundingMode.HALF_UP), splitUsd)
            } else {
                assertEquals(Amount(36.66).setScale(2, RoundingMode.HALF_UP), splitEur)
                assertEquals(Amount(33.33).setScale(2, RoundingMode.HALF_UP), splitUsd)
            }
        }
    }

    @Test
    fun splitNoRemainder() {
        val amountEUR = Amount(210)
        val amountUSD = Amount(150)
        SplitAmount.split(amountEUR, amountUSD, listOf("a", "b", "c")) { splitEur, splitUsd, _ ->
            assertEquals(Amount(70).setScale(2, RoundingMode.HALF_UP), splitEur)
            assertEquals(Amount(50).setScale(2, RoundingMode.HALF_UP), splitUsd)
        }
    }
}