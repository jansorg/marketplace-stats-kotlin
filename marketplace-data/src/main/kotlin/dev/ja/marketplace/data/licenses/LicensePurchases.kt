/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.marketplace.client.LicenseId
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * The purchases of licenses, e.g. of all customers, to find the latest purchase, the end and the status of each license.
 * A license can be renewed by another customer, e.g. after it was transferred to an organization.
 *
 * A perpetual license starts at the date of its sale and doesn't end, it's always active.
 */
class LicensePurchases {
    private val latestPurchases = Object2ObjectOpenHashMap<LicenseId, LicenseInfo>()
    private val firstStarts = Object2ObjectOpenHashMap<LicenseId, YearMonthDay>()

    /** The IDs of the added licenses. */
    val licenseIds: Set<LicenseId>
        get() = latestPurchases.keys

    /**
     * Adds a purchase. The purchase with the latest end is the latest purchase, of purchases with the same end the
     * purchase, which was added last.
     */
    fun add(license: LicenseInfo) {
        val latest = latestPurchases[license.id]
        if (latest == null || end(license) >= end(latest)) {
            latestPurchases[license.id] = license
        }
        val start = start(license)
        firstStarts[license.id] = firstStarts[license.id]?.let { minOf(it, start) } ?: start
    }

    operator fun contains(licenseId: LicenseId): Boolean = licenseId in latestPurchases

    /** @return The purchase of the license with the latest end, `null` if the license wasn't added. */
    fun latestPurchase(licenseId: LicenseId): LicenseInfo? = latestPurchases[licenseId]

    /** @return The end of the latest purchase of the license, [YearMonthDay.MAX] for a perpetual license. */
    fun latestEnd(licenseId: LicenseId): YearMonthDay? = latestPurchases[licenseId]?.let(::end)

    /** @return The start of the first purchase of the license. */
    fun firstStart(licenseId: LicenseId): YearMonthDay? = firstStarts[licenseId]

    /** @return The status of the license, it's churned if its latest purchase ended before [today]. */
    fun status(licenseId: LicenseId, today: YearMonthDay): LicenseStatus {
        return LicenseStatus.of(latestEnd(licenseId) ?: throw IllegalArgumentException("Unknown license $licenseId"), today)
    }

    companion object {
        /** @return The start of the purchase, the date of the sale of a perpetual license. */
        fun start(license: LicenseInfo): YearMonthDay = license.validity?.start ?: license.sale.date

        /** @return The end of the purchase, [YearMonthDay.MAX] for a perpetual license. */
        fun end(license: LicenseInfo): YearMonthDay = license.validity?.end ?: YearMonthDay.MAX

        fun of(licenses: Iterable<LicenseInfo>): LicensePurchases = LicensePurchases().also { purchases -> licenses.forEach(purchases::add) }
    }
}
