/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.util

import javax.money.MonetaryAmount

fun MonetaryAmount.sortValue(): Long {
    return this.number.toLong()
}