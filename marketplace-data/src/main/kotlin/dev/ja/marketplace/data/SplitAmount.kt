/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.Amount
import java.math.BigDecimal
import java.math.RoundingMode.DOWN

/**
 * Split an amount into parts without errors by rounding.
 */
object SplitAmount {
    fun <T> split(
        total: Amount,
        totalUSD: Amount,
        items: List<T>,
        block: (amount: Amount, amountUSD: Amount, item: T) -> Unit
    ) {
        val size = items.size
        val count = size.toBigDecimal()

        if (size == 0) {
            return
        }
        if (size == 1) {
            block(total, totalUSD, items[0])
            return
        }

        val totalScaled = total.setScale(10, DOWN)
        val itemAmount = (totalScaled / count).setScale(2, DOWN)
        val itemAmountLast = (totalScaled - itemAmount * (count - BigDecimal.ONE)).setScale(2, DOWN)

        val totalUSDScaled = totalUSD.setScale(10, DOWN)
        val itemAmountUSD = (totalUSDScaled / count).setScale(2, DOWN)
        val itemAmountUSDLast = (totalUSDScaled - itemAmountUSD * (count - BigDecimal.ONE)).setScale(2, DOWN)

        items.forEachIndexed { index, item ->
            when (index) {
                size - 1 -> block(itemAmountLast, itemAmountUSDLast, item)
                else -> block(itemAmount, itemAmountUSD, item)
            }
        }
    }
}