/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginCommentVotes(
    @SerialName("positive")
    val positive: Int,
    @SerialName("negative")
    val negative: Int,
    /** The vote of the user of the API key: `1` for positive, `-1` for negative, `0` without vote. */
    @SerialName("userVerdict")
    val userVerdict: Int? = null,
)