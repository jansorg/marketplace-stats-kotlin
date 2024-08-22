/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// the order of the enum values is used for sorting
@Serializable
enum class LicensePeriod(val linkSegmentName: String) {
    @SerialName("Annual")
    Annual("annual"),

    @SerialName("Monthly")
    Monthly("monthly"),
}