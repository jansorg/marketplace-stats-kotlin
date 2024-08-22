/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginVendorShort(
    @SerialName("name")
    override val name: String,
    @SerialName("isVerified")
    override val isVerified: Boolean? = null,
) : PluginVendorInformation