/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

data class ServerConfiguration(
    val userDisplayCurrencyCode: String,
    val showResellerCharges: Boolean,
    val disabledContinuityDiscount: Boolean
)
