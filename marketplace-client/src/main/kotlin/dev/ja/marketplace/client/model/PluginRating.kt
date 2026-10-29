/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.Int2IntArrayMapSerializer
import it.unimi.dsi.fastutil.ints.Int2IntMap
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
    /** The number of votes of each rating, e.g. 5 for five stars. */
    @SerialName("votes")
    @Serializable(Int2IntArrayMapSerializer::class)
    val votes: Int2IntMap,
) {
    val calculatedRatingValue: Double
        get() {
            return (weightedVotesSum + 2.0 * meanRating) / (votesCount + 2.0)
        }

    val votesCount: Int
        get() {
            return votes.values.intStream().sum()
        }

    private val weightedVotesSum: Int
        get() {
            return votes.int2IntEntrySet().sumOf { it.intKey * it.intValue }
        }
}