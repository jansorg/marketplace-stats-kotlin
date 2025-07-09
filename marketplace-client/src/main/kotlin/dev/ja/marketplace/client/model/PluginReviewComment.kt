/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
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
    @SerialName("author")
    val author: JetBrainsAccountInfo? = null,
    @SerialName("votes")
    val votes: PluginCommentVotes? = null,
    // this property is only available for replies to other plugin comments
    @SerialName("parentId")
    val parentId: Int? = null,
)