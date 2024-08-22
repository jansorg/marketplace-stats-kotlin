/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// keep order because it's used for sorting
@Serializable
enum class CustomerType {
    @SerialName("Organization")
    Organization,

    @SerialName("Personal")
    Individual,
}