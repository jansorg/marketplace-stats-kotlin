/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.CustomerInfo
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet

class CustomerTracker<T>(private val dateRange: YearMonthDayRange) {
    private val segmentedCustomers = mutableMapOf<T, MutableSet<CustomerInfo>>()
    private val customers = ObjectOpenHashSet<CustomerInfo>()
    private val customersFree = ObjectOpenHashSet<CustomerInfo>()
    private val customersPaying = ObjectOpenHashSet<CustomerInfo>()

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
        val validity = licenseInfo.validity
        if (validity != null && dateRange.end in validity) {
            val customer = licenseInfo.sale.customer

            customers += customer

            if (licenseInfo.isPaidLicense) {
                customersPaying += customer
            } else {
                customersFree += customer
            }

            segmentedCustomers.computeIfAbsent(segment) { ObjectOpenHashSet() } += customer
        }
    }
}