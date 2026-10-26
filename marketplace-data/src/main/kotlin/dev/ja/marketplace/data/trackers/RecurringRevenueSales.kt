/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.LicenseInfo

/**
 * The rule, which sale of a license counts for the recurring revenue at a day, shared by the tables and the values per
 * day of the charts: of the sales of the license, which are valid at the day, the sale with the latest end. Of sales
 * with the same end, the earlier sale counts, independent of the order of the sales.
 */
object RecurringRevenueSales {
    private val comparator: Comparator<LicenseInfo> = compareBy<LicenseInfo> { it.validity!!.end }
        .thenByDescending { it.sale.date }
        .thenByDescending { it.sale.ref }

    /** @return The sale of [a] and [b], which counts for the recurring revenue. */
    fun preferred(a: LicenseInfo, b: LicenseInfo): LicenseInfo = if (comparator.compare(b, a) > 0) b else a

    /** @return The sale, which counts for the recurring revenue, `null` without sales. */
    fun latest(sales: Iterable<LicenseInfo>): LicenseInfo? = sales.maxWithOrNull(comparator)
}
