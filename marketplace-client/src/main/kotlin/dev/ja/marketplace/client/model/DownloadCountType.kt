/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

enum class DownloadCountType(val requestPathSegment: String) {
    Downloads("downloads-count"),
    DownloadsUnique("downloads-unique"),
}