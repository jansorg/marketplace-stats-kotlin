/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.util

fun <T> List<T>.takeNullable(n: Int?): List<T> {
    return when {
        n == null -> this
        else -> this.take(n)
    }
}