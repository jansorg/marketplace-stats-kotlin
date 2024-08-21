/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProductPatchType(val jsonId: String) {
    @SerialName("mac")
    MacIntel("mac"),

    @SerialName("macM1")
    MacAppleSilicon("macM1"),

    @SerialName("unix")
    UNIX("unix"),

    @SerialName("win")
    Windows("win"),
}