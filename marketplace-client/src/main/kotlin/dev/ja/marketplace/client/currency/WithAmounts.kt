/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.currency

import javax.money.MonetaryAmount

interface WithAmounts {
    val amount: MonetaryAmount
    val amountUSD: MonetaryAmount
}