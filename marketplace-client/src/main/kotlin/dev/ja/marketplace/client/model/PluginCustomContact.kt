/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.PluginUrl
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginCustomContact(
    @SerialName("title")
    val title: String,
    @SerialName("link")
    val link: PluginUrl,
)