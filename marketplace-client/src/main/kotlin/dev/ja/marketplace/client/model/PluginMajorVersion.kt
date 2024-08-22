/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.YearMonthDay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginMajorVersion(
    @SerialName("version")
    val version: String,
    @SerialName("date")
    val date: YearMonthDay,
)