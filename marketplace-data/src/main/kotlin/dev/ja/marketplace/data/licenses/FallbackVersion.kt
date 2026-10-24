/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.PluginReleaseDate

/**
 * The version of a plugin, which the perpetual fallback license of a subscription covers.
 *
 * The rules are those of the EULA of the Marketplace: a customer, who paid for the subscription for at least the
 * 12 months before it expired, without interruptions, keeps a license of the fallback version. The fallback version is
 * the latest version, which was released before the fallback date, 12 months before the subscription expired, and its
 * bug-fix updates.
 *
 * The versions are the pairs of `release-version` and `release-date` of the plugin's updates, see
 * https://plugins.jetbrains.com/docs/marketplace/add-required-parameters.html. Updates of the same version with a
 * later `release-date` aren't covered, e.g. if the `release-date` of a version was changed.
 *
 * The subscription is the latest uninterrupted subscription of the license, a renewal must start on the day after the
 * end of the previous subscription. Its end may be in the future, the fallback version is the version of the license,
 * if it's not renewed.
 */
data class FallbackVersion(
    val licenseId: LicenseId,
    /** The first day of the latest uninterrupted subscription. */
    val subscriptionStart: YearMonthDay,
    /** The last day of the latest uninterrupted subscription. */
    val subscriptionEnd: YearMonthDay,
    /** 12 months before the subscription expires. */
    val fallbackDate: YearMonthDay,
    /** `true` if the subscription was paid for the 12 months before it expires. */
    val isEligible: Boolean,
    /** The fallback version, `null` if the license isn't eligible or if no version was released before the fallback date. */
    val release: PluginReleaseDate?,
    /** `true` if the fallback version is the latest version of the plugin. */
    val isLatestVersion: Boolean,
) {
    companion object {
        /**
         * @param licenses The licenses of all sales, only the paid subscriptions of [licenseId] are used.
         * @param releases The release dates of the versions of the plugin, which the Marketplace uses for fallback licenses.
         * @return `null` if the license has no paid subscription.
         */
        fun of(licenseId: LicenseId, licenses: Iterable<LicenseInfo>, releases: List<PluginReleaseDate>): FallbackVersion? {
            val validities = licenses
                .filter { it.id == licenseId && it.isPaidLicense }
                .mapNotNull { it.validity }
                .sortedByDescending { it.end }
            if (validities.isEmpty()) {
                return null
            }

            // the start of the latest subscription without a day without license, overlapping validities are uninterrupted
            val subscriptionEnd = validities.first().end
            var subscriptionStart = validities.first().start
            for (validity in validities.drop(1)) {
                if (validity.end.add(0, 0, 1) < subscriptionStart) {
                    break
                }
                if (validity.start < subscriptionStart) {
                    subscriptionStart = validity.start
                }
            }

            // the subscription expires at the end of its last day
            val fallbackDate = subscriptionEnd.add(0, 0, 1).add(-1, 0, 0)
            val isEligible = subscriptionStart <= fallbackDate
            val release = when {
                isEligible -> releases.filter { it.date < fallbackDate }.maxByOrNull { it.date }
                else -> null
            }
            val latestRelease = releases.maxByOrNull { it.date }

            return FallbackVersion(
                licenseId,
                subscriptionStart,
                subscriptionEnd,
                fallbackDate,
                isEligible,
                release,
                isLatestVersion = release != null && release == latestRelease,
            )
        }
    }
}
