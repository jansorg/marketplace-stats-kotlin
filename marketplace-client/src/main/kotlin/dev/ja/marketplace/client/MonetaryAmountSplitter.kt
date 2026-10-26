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
        // the last item has the remainder, the parts add up to the total
        val itemAmountLast = totalScaled - itemAmount * (count - BigDecimal.ONE)

        val totalUSDScaled = totalUSD.number.numberValue(BigDecimal::class.java).setScale(10, RoundingMode.DOWN)
        val itemAmountUSD = (totalUSDScaled / count).setScale(2, RoundingMode.DOWN)
        val itemAmountUSDLast = totalUSDScaled - itemAmountUSD * (count - BigDecimal.ONE)

        val it = items.iterator()
        while (it.hasNext()) {
            val item = it.next()
            val isLast = !it.hasNext()
            block(
                fastMoneyOf(if (isLast) itemAmountLast else itemAmount, total.currency.currencyCode),
                fastMoneyOf(if (isLast) itemAmountUSDLast else itemAmountUSD, "USD"),
                item
            )
        }
    }

    /**
     * Allocates the [total] to the parts in proportion to their [amounts], e.g. the total of a sale to its line items.
     * The parts keep their amounts if they add up to the total. If they sum up to zero, the last part gets the total.
     *
     * @return The amount of each part, they add up to the [total].
     */
    fun allocate(total: MonetaryAmount, amounts: List<MonetaryAmount>): List<MonetaryAmount> {
        if (amounts.isEmpty()) {
            return emptyList()
        }
        val totalValue = total.number.numberValue(BigDecimal::class.java)
        val values = amounts.map { it.number.numberValue(BigDecimal::class.java) }
        val sum = values.fold(BigDecimal.ZERO, BigDecimal::add)
        if (sum.compareTo(totalValue) == 0 && amounts.all { it.currency == total.currency }) {
            return amounts
        }

        val parts = when {
            sum.signum() == 0 -> values.map { BigDecimal.ZERO }
            else -> values.map { (it * totalValue).divide(sum, 2, RoundingMode.DOWN) }
        }
        // the last part has the remainder
        val allocated = parts.dropLast(1).fold(BigDecimal.ZERO, BigDecimal::add)
        return parts.dropLast(1).map { fastMoneyOf(it, total.currency.currencyCode) } +
                fastMoneyOf(totalValue - allocated, total.currency.currencyCode)
    }
}