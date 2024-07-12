/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import org.javamoney.moneta.FastMoney
import java.math.BigDecimal
import java.math.RoundingMode
import javax.money.MonetaryAmount

operator fun MonetaryAmount.plus(other: MonetaryAmount): MonetaryAmount {
    return this.add(other)
}

operator fun MonetaryAmount.minus(other: MonetaryAmount): MonetaryAmount {
    return this.subtract(other)
}

operator fun MonetaryAmount.times(other: Number): MonetaryAmount {
    require(this is FastMoney)
    val result = (other as? BigDecimal ?: BigDecimal(other.toString()))
        .multiply(this.number.numberValue(BigDecimal::class.java))
        .setScale(this.scale, RoundingMode.HALF_UP)
    return FastMoney.of(result, this.currency)
}