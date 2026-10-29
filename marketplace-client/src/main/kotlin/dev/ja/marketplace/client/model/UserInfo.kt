/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.PluginIdOrInfoSerializer
import dev.ja.marketplace.client.UserId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
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
    /**
     * The IDs of the plugins of the account. The API returns the plugins with their names and links, only their IDs
     * are kept, see [PluginIdOrInfoSerializer].
     */
    @SerialName("pluginIds")
    @JsonNames("plugins")
    val pluginIds: List<@Serializable(PluginIdOrInfoSerializer::class) PluginId> = emptyList(),
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