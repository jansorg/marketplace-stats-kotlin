/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginRating(
    @SerialName("userRating")
    val userRating: Int,
    @SerialName("meanVotes")
    val meanVotes: Int,
    @SerialName("meanRating")
    val meanRating: Double,
    @SerialName("votes")
    val votes: Map<Int, Int>,
) {
    val calculatedRatingValue: Double
        get() {
            return (weightedVotesSum + 2.0 * meanRating) / (votesCount + 2.0)
        }

    val votesCount: Int
        get() {
            return votes.values.sum()
        }

    private val weightedVotesSum: Int
        get() {
            return votes.entries.sumOf { (weight, value) -> weight * value }
        }
}