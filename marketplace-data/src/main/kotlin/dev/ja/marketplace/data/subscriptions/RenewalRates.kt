/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.subscriptions

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.data.PercentageValue

/**
 * How many paid subscriptions were renewed, grouped by the year or the month of their end, the license period and the
 * number of previous paid subscriptions of the license, e.g. the first renewal of a new license.
 */
data class RenewalRate(
    val year: Int,
    /** The month of the end of the subscriptions, `null` if the subscriptions are grouped by year. */
    val month: Int?,
    val licensePeriod: LicensePeriod,
    /** `0` for the first renewal of a new license, `1` for the second renewal, and so on. */
    val previousRenewals: Int,
    /** Subscriptions, which ended in the year or month. Subscriptions, which can still be renewed, are not included. */
    val due: Int,
    val renewed: Int,
    /** `true` if some subscriptions, which ended in the year or month, can still be renewed. */
    val isIncomplete: Boolean,
) {
    val rate: PercentageValue?
        get() = PercentageValue.rateOf(renewed, due)
}

object RenewalRates {
    /**
     * A subscription is renewed if a subscription of the same license starts later,
     * at most [graceDays] days after the end of the subscription.
     * Subscriptions, which ended less than [graceDays] days ago and aren't renewed yet, are pending.
     *
     * @param maxPreviousRenewals Subscriptions with more previous renewals are counted with this value.
     * @param byMonth Groups the subscriptions by the month of their end instead of the year.
     */
    fun of(
        licenses: Iterable<LicenseInfo>,
        today: YearMonthDay = YearMonthDay.now(),
        graceDays: Int = Marketplace.LICENSE_RENEWAL_GRACE_DAYS,
        maxPreviousRenewals: Int = 2,
        byMonth: Boolean = false,
    ): List<RenewalRate> {
        data class Key(val year: Int, val month: Int?, val period: LicensePeriod, val previousRenewals: Int)
        class Counts(var due: Int = 0, var renewed: Int = 0, var pending: Int = 0)

        val counts = HashMap<Key, Counts>()
        val subscriptionsByLicense = licenses
            .filter { it.isSubscriptionLicense && it.validity != null }
            .groupBy { it.id }

        for (subscriptions in subscriptionsByLicense.values) {
            val sorted = subscriptions.sortedWith(compareBy({ it.validity!!.start }, { it.validity!!.end }))
            var previousPaid = 0
            for ((index, subscription) in sorted.withIndex()) {
                if (!subscription.isPaidLicense) {
                    continue
                }

                val validity = subscription.validity!!
                val renewalDeadline = validity.end.add(0, 0, graceDays)
                val isRenewed = sorted.subList(index + 1, sorted.size).any {
                    it.validity!!.start > validity.start && it.validity!!.start <= renewalDeadline
                }

                // still valid, i.e. not due yet
                if (validity.end >= today) {
                    previousPaid++
                    continue
                }

                val key = Key(
                    validity.end.year,
                    if (byMonth) validity.end.month else null,
                    subscription.sale.licensePeriod,
                    minOf(previousPaid, maxPreviousRenewals),
                )
                val count = counts.getOrPut(key, ::Counts)
                when {
                    isRenewed -> {
                        count.due++
                        count.renewed++
                    }

                    renewalDeadline >= today -> count.pending++
                    else -> count.due++
                }
                previousPaid++
            }
        }

        return counts.map { (key, count) ->
            RenewalRate(key.year, key.month, key.period, key.previousRenewals, count.due, count.renewed, count.pending > 0)
        }.sortedWith(compareBy({ it.year }, { it.month }, { it.licensePeriod }, { it.previousRenewals }))
    }
}
