/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.UserId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class UserInfo(
    @SerialName("id")
    val id: UserId,
    @SerialName("name")
    val name: String,
    @SerialName("email")
    val email: String? = null,
    @SerialName("emailBanned")
    val emailBanned: Boolean? = null,
    @SerialName("hubLogin")
    val hubLogin: String? = null,
    @SerialName("icon")
    val iconUrl: String? = null,
    @SerialName("link")
    val link: String? = null,
    @SerialName("showMarketoCheckbox")
    val showMarketoCheckbox: Boolean? = null,
    /** The plugins of the account. */
    @SerialName("plugins")
    val plugins: List<PluginInfoShort> = emptyList(),
    /** The reviews and replies of the account. */
    @SerialName("comments")
    val comments: List<PluginReviewComment> = emptyList(),
    /** The releases uploaded by the account. */
    @SerialName("updates")
    val releases: List<PluginReleaseInfo> = emptyList(),
    /**
     * The TeamCity servers of the account. The API returned only empty lists so far,
     * the elements are kept as JSON until their format is known.
     */
    @SerialName("teamcityServers")
    val teamcityServers: List<JsonElement> = emptyList(),
)