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
import dev.ja.marketplace.data.licenses.Renewals

/**
 * The new paid subscription licenses of a month and how many of them were renewed.
 *
 * @param month The first day of the month, in which the first paid subscription of the licenses started.
 * @param renewals The 1st, 2nd, ... renewal of the licenses.
 */
data class LicenseCohort(
    val month: YearMonthDay,
    val licensePeriod: LicensePeriod,
    val licenses: Int,
    val renewals: List<LicenseCohortRenewal>,
)

/**
 * @param renewal `1` for the first renewal of the licenses, `2` for the second, and so on.
 * @param due Licenses with [renewal] - 1 renewals, whose renewal is decided: renewed, or not renewed within the grace period.
 * @param renewed Licenses with at least [renewal] renewals.
 * @param pending Licenses with [renewal] - 1 renewals, which can still be renewed, because they are valid or in the grace period.
 * @param decided Licenses of the cohort, for which it's known if they reached this renewal.
 *  It includes the licenses, which weren't renewed earlier.
 */
data class LicenseCohortRenewal(
    val renewal: Int,
    val due: Int,
    val renewed: Int,
    val pending: Int,
    val decided: Int,
) {
    /** Share of the licenses due for this renewal, which were renewed. */
    val renewalRate: PercentageValue?
        get() = PercentageValue.rateOf(renewed, due)

    /** Share of the decided licenses of the cohort, which were renewed at least [renewal] times. */
    val retention: PercentageValue?
        get() = PercentageValue.rateOf(renewed, decided)

    /** `true` if the values may still change, because licenses of the cohort can still be renewed. */
    val isIncomplete: Boolean
        get() = pending > 0
}

object LicenseCohorts {
    /**
     * Groups the subscription licenses by the month of their first paid subscription and the license period of that sale.
     * A subscription is renewed, like in [RenewalRates], if another paid subscription of the same license starts later,
     * after at most [graceDays] days without a license, see [Renewals].
     *
     * @param maxRenewals The maximum number of renewals of each cohort.
     */
    fun of(
        licenses: Iterable<LicenseInfo>,
        maxRenewals: Int,
        today: YearMonthDay = YearMonthDay.now(),
        graceDays: Int = Marketplace.LICENSE_RENEWAL_GRACE_DAYS,
    ): List<LicenseCohort> {
        data class Key(val month: YearMonthDay, val period: LicensePeriod)
        data class LicenseChain(val renewals: Int, val isOpen: Boolean)

        val chains = HashMap<Key, MutableList<LicenseChain>>()
        val subscriptionsByLicense = licenses
            .filter { it.isSubscriptionLicense && it.validity != null }
            .groupBy { it.id }

        for (subscriptions in subscriptionsByLicense.values) {
            val sorted = subscriptions.sortedWith(compareBy({ it.validity!!.start }, { it.validity!!.end }))
            val firstIndex = sorted.indexOfFirst { it.isPaidLicense }
            if (firstIndex < 0) {
                continue
            }

            var current = sorted[firstIndex]
            var renewals = 0
            while (true) {
                val validity = current.validity!!
                val deadline = Renewals.deadline(validity.end, graceDays)
                // free subscriptions aren't renewals of the paid subscriptions
                current = sorted.firstOrNull { it.isPaidLicense && it.validity!!.start > validity.start && it.validity!!.start <= deadline } ?: break
                renewals++
            }

            val first = sorted[firstIndex]
            val start = first.validity!!.start
            val key = Key(YearMonthDay(start.year, start.month, 1), first.sale.licensePeriod)
            val isOpen = Renewals.deadline(current.validity!!.end, graceDays) >= today
            chains.getOrPut(key, ::mutableListOf) += LicenseChain(renewals, isOpen)
        }

        return chains.map { (key, licenseChains) ->
            val renewals = (1..maxRenewals).map { renewal ->
                var due = 0
                var renewed = 0
                var pending = 0
                var decided = 0
                for ((count, isOpen) in licenseChains) {
                    when {
                        count >= renewal -> {
                            due++
                            renewed++
                            decided++
                        }

                        count == renewal - 1 && isOpen -> pending++
                        count == renewal - 1 -> {
                            due++
                            decided++
                        }

                        // not renewed earlier
                        !isOpen -> decided++
                    }
                }
                LicenseCohortRenewal(renewal, due, renewed, pending, decided)
            }.takeWhile { it.due > 0 || it.pending > 0 }

            LicenseCohort(key.month, key.period, licenseChains.size, renewals)
        }.sortedWith(compareBy({ it.month }, { it.licensePeriod }))
    }
}
