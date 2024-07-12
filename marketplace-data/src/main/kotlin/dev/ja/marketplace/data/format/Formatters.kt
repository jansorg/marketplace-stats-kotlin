/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.format

import java.util.*
import javax.money.format.MonetaryFormats

object Formatters {
    val MonetaryAmount = MonetaryFormats.getAmountFormat(Locale.getDefault())
}