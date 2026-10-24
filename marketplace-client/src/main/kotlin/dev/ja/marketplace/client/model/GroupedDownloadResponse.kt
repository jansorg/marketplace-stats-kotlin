/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Downloads grouped by two dimensions, e.g. by product and month: a series of [dimension] values for each group of [groupDimension].
 */
@Serializable
data class GroupedDownloadResponse(
    @SerialName("measure")
    val measure: String,
    // strings instead of DownloadDimension, so that unknown dimensions of the API don't break the request
    @SerialName("dim1")
    val dimension: String,
    @SerialName("dim2")
    val groupDimension: String,
    @SerialName("data")
    val groups: List<DownloadResponseGroup>,
    @SerialName("filters")
    val filters: List<DownloadFilter> = emptyList(),
)

@Serializable
data class DownloadResponseGroup(
    /** Name of the group, e.g. the product code or the ID of the plugin release. Downloads without a value are in group `unknown`. */
    @SerialName("dimension")
    val name: String,
    /** Human-readable name of the group, e.g. the name of the product. */
    @SerialName("dimensionComment")
    val comment: String? = null,
    @SerialName("serie")
    val serie: List<DownloadResponseItem>,
)
