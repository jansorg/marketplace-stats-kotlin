/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import io.ktor.http.*

fun String?.asNullableUrl(): Url? {
    this ?: return null
    return try {
        Url(this)
    } catch (e: Exception) {
        null
    }
}