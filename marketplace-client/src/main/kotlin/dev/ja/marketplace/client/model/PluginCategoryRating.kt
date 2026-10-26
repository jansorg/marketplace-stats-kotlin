/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The rating of a category of the reviews of a plugin, e.g. of the performance.
 *
 * @param mean The average rating of the category, from 1 to 5.
 * @param votes The number of reviews, which rated the category.
 */
@Serializable
data class PluginCategoryRating(
    @SerialName("mean")
    val mean: Double,
    @SerialName("votes")
    val votes: Int,
)
