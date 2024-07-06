/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.CustomerInfo
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.data.LicenseInfo

class CustomerTracker<T>(private val dateRange: YearMonthDayRange) {
    private val segmentedCustomers = mutableMapOf<T, MutableSet<CustomerInfo>>()
    private val customers = mutableSetOf<CustomerInfo>()
    private val customersFree = mutableSetOf<CustomerInfo>()
    private val customersPaying = mutableSetOf<CustomerInfo>()

    val totalCustomerCount: Int
        get() {
            return customers.size
        }

    val freeCustomerCount: Int
        get() {
            return customersFree.size
        }

    val payingCustomerCount: Int
        get() {
            return customersPaying.size
        }

    fun segmentCustomerCount(segment: T): Int {
        return segmentedCustomers[segment]?.size ?: 0
    }

    fun add(segment: T, licenseInfo: LicenseInfo) {
        if (dateRange.end in licenseInfo.validity) {
            val customer = licenseInfo.sale.customer

            customers += customer

            if (licenseInfo.isPaidLicense) {
                customersPaying += customer
            } else {
                customersFree += customer
            }

            segmentedCustomers.computeIfAbsent(segment) { mutableSetOf() } += customer
        }
    }
}