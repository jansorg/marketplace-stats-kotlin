/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.CDateSerializer
import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.PluginIdOrInfoSerializer
import dev.ja.marketplace.client.PluginReleaseId
import dev.ja.marketplace.client.PluginReviewId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)
@Serializable
data class PluginReviewComment(
    @SerialName("id")
    val id: PluginReviewId,
    @SerialName("cdate")
    @Serializable(CDateSerializer::class)
    val createdTimestamp: Instant? = null,
    @SerialName("comment")
    val comment: String,
    /** The API returns the plugin with its name and link, only its ID is kept, see [PluginIdOrInfoSerializer]. */
    @SerialName("pluginId")
    @JsonNames("plugin")
    @Serializable(PluginIdOrInfoSerializer::class)
    val pluginId: PluginId,
    @SerialName("rating")
    val rating: Short,
    @SerialName("repliesCount")
    val repliesCount: Int,
    // missing in the comments of the account, see UserInfo
    @SerialName("vendor")
    val vendor: Boolean = false,
    @SerialName("markedAsSpam")
    val markedAsSpam: Boolean = false,
    @SerialName("author")
    val author: JetBrainsAccountInfo? = null,
    @SerialName("votes")
    val votes: PluginCommentVotes? = null,
    // this property is only available for replies to other plugin comments
    @SerialName("parentId")
    val parentId: Int? = null,
    /**
     * The state of the request of the vendor to resolve the review, e.g. `INIT` or `RESOLVED`.
     * A string, so that new states don't break the client, see [isResolved].
     */
    @SerialName("resolveState")
    val resolveState: String? = null,
    @SerialName("isResolveRequested")
    val isResolveRequested: Boolean = false,
    @SerialName("isDeleted")
    val isDeleted: Boolean = false,
    /** Ratings of categories of the plugin, e.g. of the performance, with values from 1 to 5. */
    @SerialName("subRating")
    val subRating: Map<PluginRatingCategory, Int>? = null,
    /** The IDE version of the author, if it's known. */
    @SerialName("ideVersion")
    val ideVersion: String? = null,
    /** The ID of the release of the plugin, which the author used. */
    @SerialName("updateId")
    val releaseId: PluginReleaseId? = null,
    @SerialName("updateVersion")
    val releaseVersion: String? = null,
) {
    /** A resolved review doesn't count for the rating of the plugin. */
    val isResolved: Boolean
        get() = resolveState == RESOLVE_STATE_RESOLVED

    companion object {
        const val RESOLVE_STATE_RESOLVED: String = "RESOLVED"
    }
}