/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

enum class ContinuityDiscount(val percent: Short, val factor: Double) {
    None(0, 1.0),
    FirstYear(0, 1.0),
    SecondYear(20, 0.8),
    ThirdYear(40, 0.6),
}