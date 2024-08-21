/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProductReleaseDownload(
    @SerialName("link")
    val link: String,
    @SerialName("checksumLink")
    val checksumUrl: String,
    @SerialName("size")
    val fileSizeBytes: Long,
    @SerialName("signedChecksumLink")
    val signedChecksumLink: String? = null,
)