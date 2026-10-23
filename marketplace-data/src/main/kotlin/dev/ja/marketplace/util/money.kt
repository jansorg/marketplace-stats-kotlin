/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.util

import dev.ja.marketplace.data.sortValue
import java.math.BigDecimal
import javax.money.MonetaryAmount

/**
 * @return The amount in cents, to sort amounts which only differ in cents.
 */
fun MonetaryAmount.sortValue(): Long {
    return this.number.numberValue(BigDecimal::class.java).sortValue()
}
