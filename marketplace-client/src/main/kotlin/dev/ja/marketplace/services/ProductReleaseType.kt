/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProductReleaseType(val jsonId: String) {
    @SerialName("release")
    Release("release"),

    @SerialName("rc")
    ReleaseCandidate("rc"),

    @SerialName("eap")
    EAP("eap"),

    @SerialName("preview")
    Preview("preview"),
}