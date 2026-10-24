/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.ratings

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.JetBrainsAccountInfo
import dev.ja.marketplace.client.model.PluginInfoShort
import dev.ja.marketplace.client.model.PluginRating
import dev.ja.marketplace.client.model.PluginReviewComment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Instant

class RatingHistoryTest {
    private val reviews = listOf(
        review(1, "2024-03-10T10:00:00Z", 5),
        review(2, "2024-08-01T10:00:00Z", 2),
        // not counted: resolved, deleted, without rating and spam
        review(3, "2024-09-01T10:00:00Z", 1, resolveState = "RESOLVED"),
        review(4, "2024-09-02T10:00:00Z", 1, isDeleted = true),
        review(5, "2024-09-03T10:00:00Z", 0),
        review(6, "2024-09-04T10:00:00Z", 1, spam = true),
        review(7, "2025-02-01T10:00:00Z", 4),
    )

    /** The votes of the Marketplace: the counted reviews and two ratings without a review text. */
    private val rating = PluginRating(0, 2, 4.0, mapOf(5 to 3, 4 to 1, 2 to 1))

    private val history = RatingHistory(reviews, rating, firstDay = YearMonthDay(2024, 1, 1))

    @Test
    fun ratingsWithoutDateAreTheVotesWithoutReview() {
        assertEquals(mapOf(5 to 2), history.undatedVotes)
    }

    @Test
    fun votesAtADay() {
        assertEquals(emptyMap<Int, Int>(), history.votesAt(YearMonthDay(2023, 12, 31)))
        // the ratings without date are counted from the first day
        assertEquals(mapOf(5 to 2), history.votesAt(YearMonthDay(2024, 1, 1)))
        assertEquals(mapOf(5 to 3, 2 to 1), history.votesAt(YearMonthDay(2024, 12, 31)))
        assertEquals(rating.votes, history.votesAt(YearMonthDay(2025, 6, 1)))
    }

    @Test
    fun ratingOfTodayIsTheRatingOfTheMarketplace() {
        assertNull(history.ratingAt(YearMonthDay(2023, 12, 31)))
        val expected = BigDecimal.valueOf(rating.calculatedRatingValue).setScale(2, RoundingMode.HALF_UP)
        assertEquals(expected, history.ratingAt(YearMonthDay(2025, 6, 1)))
        // (3 * 5 + 1 * 2 + 2 * 4.0) / (4 + 2)
        assertEquals(BigDecimal("4.17"), history.ratingAt(YearMonthDay(2024, 12, 31)))
    }

    @Test
    fun combinedRatingOfSeveralPlugins() {
        val other = RatingHistory(listOf(review(10, "2024-05-01T10:00:00Z", 1)), PluginRating(0, 2, 4.0, mapOf(1 to 1)), YearMonthDay(2024, 1, 1))
        // (2 * 5 + 1 * 5 + 1 * 1 + 2 * 4.0) / (4 + 2)
        assertEquals(BigDecimal("4.00"), RatingHistory.combinedRatingAt(listOf(history, other), YearMonthDay(2024, 6, 1)))
        assertNull(RatingHistory.combinedRatingAt(emptyList(), YearMonthDay(2024, 6, 1)))
    }

    private fun review(
        id: Long,
        timestamp: String,
        rating: Int,
        resolveState: String = "INIT",
        isDeleted: Boolean = false,
        spam: Boolean = false,
    ): PluginReviewComment {
        return PluginReviewComment(
            id = id,
            createdTimestamp = Instant.parse(timestamp),
            comment = "Review $id",
            plugin = PluginInfoShort(1, "Plugin"),
            rating = rating.toShort(),
            repliesCount = 0,
            vendor = false,
            markedAsSpam = spam,
            author = JetBrainsAccountInfo("author-$id", "Author $id"),
            resolveState = resolveState,
            isDeleted = isDeleted,
        )
    }
}
