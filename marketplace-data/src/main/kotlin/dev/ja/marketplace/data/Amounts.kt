/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import dev.ja.marketplace.client.Amount
import dev.ja.marketplace.client.WithAmounts
import dev.ja.marketplace.services.Currency
import java.math.BigDecimal

data class Amounts(
    override val amount: Amount,
    override val currency: Currency,
    override val amountUSD: Amount
) : WithAmounts {
    companion object {
        fun zero(currency: Currency) = Amounts(BigDecimal.ZERO, currency, BigDecimal.ZERO)
    }
}
