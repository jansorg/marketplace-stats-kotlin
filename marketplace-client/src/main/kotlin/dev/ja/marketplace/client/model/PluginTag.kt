/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginTag(
    @SerialName("id")
    val id: Int,
    @SerialName("name")
    val name: String,
    @SerialName("privileged")
    val privileged: Boolean,
    @SerialName("searchable")
    val searchable: Boolean,
    @SerialName("link")
    val link: String,
)