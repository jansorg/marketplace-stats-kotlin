/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

/**
 * Information about the application for the templates, [BuildConfig] is internal.
 */
object AppInfo {
    val version: String = BuildConfig.APP_VERSION.trim()
}
