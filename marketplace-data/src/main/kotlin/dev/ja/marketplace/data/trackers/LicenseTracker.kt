/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.data.LicenseInfo

class LicenseTracker<T>(private val dateRange: YearMonthDayRange) {
    private val segmentedLicenses = mutableMapOf<T, MutableSet<LicenseInfo>>()
    private val licenses = mutableSetOf<LicenseInfo>()
    private val licensesFree = mutableSetOf<LicenseInfo>()
    private val licensesPaying = mutableSetOf<LicenseInfo>()

    val totalLicenseCount: Int
        get() {
            return licenses.size
        }

    val freeLicensesCount: Int
        get() {
            return licensesFree.size
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
        if (dateRange.end in licenseInfo.validity) {
            licenses += licenseInfo

            if (licenseInfo.isPaidLicense) {
                licensesPaying += licenseInfo
            } else {
                licensesFree += licenseInfo
            }

            segmentedLicenses.computeIfAbsent(segment) { mutableSetOf() } += licenseInfo
        }
    }
}