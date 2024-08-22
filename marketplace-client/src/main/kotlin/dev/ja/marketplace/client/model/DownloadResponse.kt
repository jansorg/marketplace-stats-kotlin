/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DownloadResponse(
    @SerialName("measure")
    val measure: String,
    @SerialName("filters")
    val filters: List<DownloadFilter>,
    @SerialName("dim1")
    val dimension: DownloadDimension,
    @SerialName("data")
    val data: DownloadResponseData
)