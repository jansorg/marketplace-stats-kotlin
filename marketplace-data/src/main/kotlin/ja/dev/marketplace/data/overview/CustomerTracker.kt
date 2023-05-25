package ja.dev.marketplace.data.overview

import ja.dev.marketplace.client.CustomerInfo
import ja.dev.marketplace.client.YearMonthDayRange
import ja.dev.marketplace.data.LicenseInfo

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