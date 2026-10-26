/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.TolerantEnumSerializer
import kotlinx.serialization.Serializable

/**
 * The type of a filter of the download statistics. The responses also list the date range as filters.
 * Types, which the client doesn't know, are [Unknown].
 */
@Serializable(DownloadFilterTypeSerializer::class)
enum class DownloadFilterType(val requestParameterName: String) {
    Plugin("plugin"),
    Update("update"),
    Country("country"),
    ProductCode("productCode"),
    MajorVersion("versionMajor"),
    StartDate("startDate"),
    EndDate("endDate"),

    /** A type, which the Marketplace added later. It isn't sent with requests. */
    Unknown("unknown"),
}

object DownloadFilterTypeSerializer : TolerantEnumSerializer<DownloadFilterType>(
    "DownloadFilterType", DownloadFilterType.entries, DownloadFilterType.Unknown, DownloadFilterType::requestParameterName,
)
