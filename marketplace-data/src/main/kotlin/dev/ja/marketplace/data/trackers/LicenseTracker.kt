/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDayRange

class LicenseTracker<T>(private val dateRange: YearMonthDayRange) {
    private val segmentedLicenses = mutableMapOf<T, MutableList<LicenseInfo>>()
    private val licenses = mutableSetOf<LicenseId>()
    private val licensesPaying = mutableSetOf<LicenseId>()

    val totalLicenseCount: Int
        get() {
            return licenses.size
        }

    val paidLicensesCount: Int
        get() {
            return licensesPaying.size
        }

    fun segmentCustomerCount(segment: T): Int {
        return segmentedLicenses[segment]?.size ?: 0
    }

    fun getSegment(segment: T): Collection<LicenseInfo> {
        return segmentedLicenses[segment] ?: emptyList()
    }

    fun add(segment: T, licenseInfo: LicenseInfo) {
        val validity = licenseInfo.validity
        if (validity != null && dateRange.end in validity) {
            licenses += licenseInfo.id

            if (licenseInfo.isPaidLicense) {
                licensesPaying += licenseInfo.id
            }

            segmentedLicenses.computeIfAbsent(segment) {
                ArrayList(500)
            } += licenseInfo
        }
    }
}