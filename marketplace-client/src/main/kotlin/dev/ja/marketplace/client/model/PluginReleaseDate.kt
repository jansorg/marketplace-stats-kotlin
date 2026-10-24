/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.YearMonthDateSerializer
import dev.ja.marketplace.client.YearMonthDay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The release date of a version of a paid plugin, as used by the Marketplace for perpetual fallback licenses.
 */
@Serializable
data class PluginReleaseDate(
    @SerialName("version")
    val version: String,
    @SerialName("date")
    @Serializable(YearMonthDateSerializer::class)
    val date: YearMonthDay,
)
