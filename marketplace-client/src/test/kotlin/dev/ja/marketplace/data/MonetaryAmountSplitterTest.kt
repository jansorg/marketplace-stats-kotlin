/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.MonetaryAmountSplitter
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class MonetaryAmountSplitterTest {
    @Test
    fun splitAmount() {
        val amountEUR = FastMoney.of(1.1, "EUR")
        val amountUSD = FastMoney.of(1.0, "USD")
        MonetaryAmountSplitter.split(amountEUR, amountUSD, listOf("a", "b", "c")) { splitEur, splitUsd, item ->
            if (item == "c") {
                Assertions.assertEquals(FastMoney.of(0.38, "EUR"), splitEur)
                Assertions.assertEquals(FastMoney.of(0.34, "USD"), splitUsd)
            } else {
                Assertions.assertEquals(FastMoney.of(0.36, "EUR"), splitEur)
                Assertions.assertEquals(FastMoney.of(0.33, "USD"), splitUsd)
            }
        }
    }

    @Test
    fun splitAmount2() {
        val amountEUR = FastMoney.of(110, "EUR")
        val amountUSD = FastMoney.of(100, "USD")
        MonetaryAmountSplitter.split(amountEUR, amountUSD, listOf("a", "b", "c")) { splitEur, splitUsd, item ->
            if (item == "c") {
                Assertions.assertEquals(FastMoney.of(36.68, "EUR"), splitEur)
                Assertions.assertEquals(FastMoney.of(33.34, "USD"), splitUsd)
            } else {
                Assertions.assertEquals(FastMoney.of(36.66, "EUR"), splitEur)
                Assertions.assertEquals(FastMoney.of(33.33, "USD"), splitUsd)
            }
        }
    }

    @Test
    fun splitNoRemainder() {
        val amountEUR = FastMoney.of(210, "EUR")
        val amountUSD = FastMoney.of(150, "USD")
        MonetaryAmountSplitter.split(amountEUR, amountUSD, listOf("a", "b", "c")) { splitEur, splitUsd, _ ->
            Assertions.assertEquals(FastMoney.of(70, "EUR"), splitEur)
            Assertions.assertEquals(FastMoney.of(50, "USD"), splitUsd)
        }
    }

    @Test
    fun splitKeepsTheRemainderBelowCents() {
        // converted amounts have up to 5 decimal places
        val parts = mutableListOf<FastMoney>()
        MonetaryAmountSplitter.split(FastMoney.of(100.005, "EUR"), FastMoney.of(100.005, "USD"), listOf("a", "b")) { splitEur, _, _ ->
            parts += FastMoney.from(splitEur)
        }
        Assertions.assertEquals(listOf(FastMoney.of(50, "EUR"), FastMoney.of(50.005, "EUR")), parts)
    }

    @Test
    fun allocateKeepsMatchingAmounts() {
        val amounts = listOf(FastMoney.of(-50, "USD"), FastMoney.of(-30, "USD"))
        Assertions.assertEquals(amounts, MonetaryAmountSplitter.allocate(FastMoney.of(-80, "USD"), amounts))
    }

    @Test
    fun allocateInProportionToTheAmounts() {
        val amounts = listOf(FastMoney.of(50, "USD"), FastMoney.of(50, "USD"), FastMoney.of(100, "USD"))
        Assertions.assertEquals(
            listOf(FastMoney.of(2.5, "USD"), FastMoney.of(2.5, "USD"), FastMoney.of(5, "USD")),
            MonetaryAmountSplitter.allocate(FastMoney.of(10, "USD"), amounts).map(FastMoney::from),
        )
        // the remainder of the rounding is part of the last amount
        Assertions.assertEquals(
            listOf(FastMoney.of(3.33, "USD"), FastMoney.of(3.33, "USD"), FastMoney.of(3.34, "USD")),
            MonetaryAmountSplitter.allocate(FastMoney.of(10, "USD"), List(3) { FastMoney.of(20, "USD") }).map(FastMoney::from),
        )
        // a sale with a total of zero, e.g. after a manual change
        Assertions.assertEquals(
            List(2) { FastMoney.of(0, "USD") },
            MonetaryAmountSplitter.allocate(FastMoney.of(0, "USD"), List(2) { FastMoney.of(20, "USD") }).map(FastMoney::from),
        )
    }
}
