/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.CDateSerializer
import dev.ja.marketplace.client.PluginReviewId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
@Serializable
data class PluginReviewComment(
    @SerialName("id")
    val id: PluginReviewId,
    @SerialName("cdate")
    @Serializable(CDateSerializer::class)
    val createdTimestamp: Instant? = null,
    @SerialName("comment")
    val comment: String,
    @SerialName("plugin")
    val plugin: PluginInfoShort,
    @SerialName("rating")
    val rating: Short,
    @SerialName("repliesCount")
    val repliesCount: Int,
    @SerialName("vendor")
    val vendor: Boolean,
    @SerialName("markedAsSpam")
    val markedAsSpam: Boolean,
    /** The state of the resolution of the review, e.g. `INIT` or `RESOLVED`. Resolved reviews don't count for the rating. */
    @SerialName("resolveState")
    val resolveState: String? = null,
    @SerialName("isResolveRequested")
    val isResolveRequested: Boolean = false,
    @SerialName("isDeleted")
    val isDeleted: Boolean = false,
    @SerialName("author")
    val author: JetBrainsAccountInfo? = null,
    @SerialName("votes")
    val votes: PluginCommentVotes? = null,
    // this property is only available for replies to other plugin comments
    @SerialName("parentId")
    val parentId: Int? = null,
)