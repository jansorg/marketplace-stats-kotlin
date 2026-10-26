/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDayRange
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet

class LicenseTracker<T>(private val dateRange: YearMonthDayRange) {
    // the IDs of the licenses of each segment, a license with several valid sales is counted once
    private val segmentedLicenses = mutableMapOf<T, MutableSet<LicenseId>>()
    private val licenses = ObjectOpenHashSet<LicenseId>()
    private val licensesPaying = ObjectOpenHashSet<LicenseId>()

    val totalLicenseCount: Int
        get() {
            return licenses.size
        }

    val paidLicensesCount: Int
        get() {
            return licensesPaying.size
        }

    fun segmentLicenseCount(segment: T): Int {
        return segmentedLicenses[segment]?.size ?: 0
    }

    fun add(segment: T, licenseInfo: LicenseInfo) {
        val validity = licenseInfo.validity
        if (validity != null && dateRange.end in validity) {
            licenses += licenseInfo.id

            if (licenseInfo.isPaidLicense) {
                licensesPaying += licenseInfo.id
            }

            segmentedLicenses.computeIfAbsent(segment) { ObjectOpenHashSet() } += licenseInfo.id
        }
    }
}