/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import org.javamoney.moneta.FastMoney
import java.math.BigDecimal
import java.math.RoundingMode
import javax.money.MonetaryAmount

/**
 * Split an amount into parts without errors by rounding.
 */
internal object MonetaryAmountSplitter {
    fun <T> split(
        total: MonetaryAmount,
        totalUSD: MonetaryAmount,
        items: List<T>,
        block: (amount: MonetaryAmount, amountUSD: MonetaryAmount, item: T) -> Unit
    ) {
        val size = items.size
        when (size) {
            0 -> return
            1 -> {
                block(total, totalUSD, items[0])
                return
            }
        }

        val count = size.toBigDecimal()

        val totalScaled = total.number.numberValue(BigDecimal::class.java).setScale(10, RoundingMode.DOWN)
        val itemAmount = (totalScaled / count).setScale(2, RoundingMode.DOWN)
        val itemAmountLast = (totalScaled - itemAmount * (count - BigDecimal.ONE)).setScale(2, RoundingMode.DOWN)

        val totalUSDScaled = totalUSD.number.numberValue(BigDecimal::class.java).setScale(10, RoundingMode.DOWN)
        val itemAmountUSD = (totalUSDScaled / count).setScale(2, RoundingMode.DOWN)
        val itemAmountUSDLast = (totalUSDScaled - itemAmountUSD * (count - BigDecimal.ONE)).setScale(2, RoundingMode.DOWN)

        val it = items.iterator()
        while (it.hasNext()) {
            val item = it.next()
            val isLast = !it.hasNext()
            block(
                FastMoney.of(if (isLast) itemAmountLast else itemAmount, total.currency),
                FastMoney.of(if (isLast) itemAmountUSDLast else itemAmountUSD, "USD"),
                item
            )
        }
    }
}