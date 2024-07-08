/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Country(
    @SerialName("iso")
    val isoCode: String,
    @SerialName("printableName")
    val printableName: String,
    @SerialName("localName")
    val localName: String,
    @SerialName("allow_personal_quotes")
    val allowPersonalQuotes: Boolean,
    @SerialName("region")
    val region: String,
    @SerialName("salesRegion")
    val salesRegion: String,
)
