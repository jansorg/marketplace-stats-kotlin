/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.overview

import dev.ja.marketplace.client.CustomerInfo
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.data.LicenseInfo

class CustomerTracker<T>(private val dateRange: YearMonthDayRange) {
    private val segmentedCustomers = mutableMapOf<T, MutableSet<CustomerInfo>>()
    private val customers = mutableSetOf<CustomerInfo>()

    val totalCustomerCount: Int
        get() {
            return customers.size
        }

    fun segmentCustomerCount(segment: T): Int {
        return segmentedCustomers[segment]?.size ?: 0
    }

    fun add(segment: T, licenseInfo: LicenseInfo) {
        if (dateRange.end in licenseInfo.validity) {
            customers += licenseInfo.sale.customer
            segmentedCustomers.computeIfAbsent(segment) { mutableSetOf() } += licenseInfo.sale.customer
        }
    }
}