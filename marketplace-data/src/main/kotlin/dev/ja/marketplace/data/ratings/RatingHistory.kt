/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.ratings

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.PluginRating
import dev.ja.marketplace.client.model.PluginReviewComment
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The rating of a plugin at a past date, an approximation.
 *
 * The rating of the Marketplace is calculated from the votes, see [PluginRating.calculatedRatingValue]. The reviews
 * with a text have a date, they are counted from that date. Reviews without a rating, resolved, deleted and spam
 * reviews aren't counted, like by the Marketplace. Ratings without a text aren't returned by the API, only their number
 * in [PluginRating.votes] is known. They are counted from [firstDay], the earliest date they could have been made, so
 * that the rating of today is the rating of the Marketplace.
 *
 * @param firstDay The first day of the plugin, e.g. its first month with sales.
 */
class RatingHistory(
    reviews: List<PluginReviewComment>,
    private val rating: PluginRating,
    private val firstDay: YearMonthDay,
) {
    /** The ratings of the counted reviews by date. */
    private val datedRatings: List<Pair<YearMonthDay, Int>> = reviews
        .filter(::isCountedReview)
        .mapNotNull { review -> review.createdTimestamp?.let { YearMonthDay.of(it) to review.rating.toInt() } }
        .sortedBy { it.first }

    /** The number of ratings without a date by their number of stars. */
    val undatedVotes: Map<Int, Int> = run {
        val datedVotes = datedRatings.groupingBy { it.second }.eachCount()
        rating.votes.mapValues { (stars, count) -> (count - (datedVotes[stars] ?: 0)).coerceAtLeast(0) }.filterValues { it > 0 }
    }

    /**
     * @return The votes by their number of stars, which were made until the day.
     */
    fun votesAt(day: YearMonthDay): Map<Int, Int> {
        val votes = HashMap<Int, Int>()
        if (day >= firstDay) {
            undatedVotes.forEach { (stars, count) -> votes.merge(stars, count, Int::plus) }
        }
        for ((date, stars) in datedRatings) {
            if (date > day) {
                break
            }
            votes.merge(stars, 1, Int::plus)
        }
        return votes
    }

    /**
     * @return The rating at the end of the day, `null` if the plugin had no votes yet.
     */
    fun ratingAt(day: YearMonthDay): BigDecimal? = ratingOf(votesAt(day), rating)

    companion object {
        /** The ID of the rating of a plugin at each day, for the charts, see [dev.ja.marketplace.data.DataTableColumn.dailyChartSeries]. */
        const val DAILY_SERIES_ID: String = "rating"

        /** Tooltip of the columns with the rating at a past date. */
        const val TOOLTIP: String = "Rating at the end of the year, the current year at today. " +
                "Calculated from the dated reviews, ratings without a review text are counted from the first month of the plugin."

        /**
         * The Marketplace counts reviews with a rating, which aren't resolved, deleted or spam. Replies aren't reviews.
         */
        fun isCountedReview(review: PluginReviewComment): Boolean {
            return review.rating in 1..5 && review.parentId == null && !review.markedAsSpam && !review.isDeleted &&
                    !review.resolveState.equals("RESOLVED", ignoreCase = true)
        }

        /**
         * @param votes The votes by their number of stars.
         * @param marketplaceRating A rating of the Marketplace, its mean rating and number of votes of the Marketplace are used.
         * @return The rating of the votes with two decimals, `null` without votes.
         */
        fun ratingOf(votes: Map<Int, Int>, marketplaceRating: PluginRating): BigDecimal? {
            if (votes.values.sum() == 0) {
                return null
            }
            val value = marketplaceRating.copy(votes = votes).calculatedRatingValue
            return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)
        }

        /**
         * @return The rating of several plugins at the day, calculated from the votes of all plugins.
         */
        fun combinedRatingAt(histories: List<RatingHistory>, day: YearMonthDay): BigDecimal? {
            val first = histories.firstOrNull() ?: return null
            val votes = HashMap<Int, Int>()
            histories.forEach { history -> history.votesAt(day).forEach { (stars, count) -> votes.merge(stars, count, Int::plus) } }
            return ratingOf(votes, first.rating)
        }
    }
}
