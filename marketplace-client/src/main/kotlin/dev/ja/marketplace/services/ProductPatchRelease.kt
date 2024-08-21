/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProductPatchRelease(
    @SerialName("fromBuild")
    val fromBuild: String,
    @SerialName("link")
    val downloadLink: String,
    @SerialName("size")
    val downloadFileSizeBytes: Long,
    @SerialName("checksumLink")
    val checksumLink: String,
)