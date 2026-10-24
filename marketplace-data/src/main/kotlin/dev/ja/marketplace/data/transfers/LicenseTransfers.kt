/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.transfers

import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.CustomerInfo

/**
 * A license, which was bought by another customer than the previous purchase of the same license,
 * e.g. when an individual moves the license to the organization of the employer.
 *
 * @param date The date of the sale of the new customer.
 * @param previousValidUntil The end of the last subscription of the previous customer, `null` for perpetual licenses.
 * @param isFromCustomerActive `true` if the previous customer still has an active license.
 *  If `false`, the previous customer appears as churned, although the license is still used.
 */
data class LicenseTransfer(
    val licenseId: LicenseId,
    val date: YearMonthDay,
    val from: CustomerInfo,
    val to: CustomerInfo,
    val previousValidUntil: YearMonthDay?,
    val isFromCustomerActive: Boolean,
)

object LicenseTransfers {
    /**
     * @return The transfers of all licenses, sorted by date.
     */
    fun find(licenses: Iterable<LicenseInfo>, today: YearMonthDay = YearMonthDay.now()): List<LicenseTransfer> {
        val all = licenses.toList()
        val activeCustomers = all
            .filter { it.validity == null || it.validity!!.end >= today }
            .mapTo(HashSet()) { it.sale.customer.code }

        return all.groupBy { it.id }.flatMap { (licenseId, purchases) ->
            purchases.sortedWith(compareBy({ it.sale.date }, { it.validity?.start }))
                .zipWithNext()
                .filter { (previous, next) -> previous.sale.customer.code != next.sale.customer.code }
                .map { (previous, next) ->
                    val previousCustomer = previous.sale.customer
                    val previousValidUntil = purchases
                        .filter { it.sale.customer.code == previousCustomer.code && it.sale.date <= previous.sale.date }
                        .mapNotNull { it.validity?.end }
                        .maxOrNull()
                    LicenseTransfer(
                        licenseId,
                        next.sale.date,
                        previousCustomer,
                        next.sale.customer,
                        previousValidUntil,
                        previousCustomer.code in activeCustomers,
                    )
                }
        }.sortedWith(compareBy({ it.date }, { it.licenseId }))
    }
}
