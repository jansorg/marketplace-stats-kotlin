/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DownloadFilterType(val requestParameterName: String) {
    @SerialName("plugin")
    Plugin("plugin"),

    @SerialName("update")
    Update("update"),

    @SerialName("country")
    Country("country"),

    @SerialName("productCode")
    ProductCode("productCode"),

    @SerialName("versionMajor")
    MajorVersion("versionMajor"),
}