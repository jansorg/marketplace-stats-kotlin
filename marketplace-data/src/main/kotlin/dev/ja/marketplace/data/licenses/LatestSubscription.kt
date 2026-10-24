/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.licenses

import dev.ja.marketplace.client.YearMonthDayRange

/**
 * @param validities The validities of the purchases of a license.
 * @return The latest uninterrupted subscription of the license: from the first day of the purchases without a day
 *  without license until the end of the latest purchase, `null` if there are no validities. Overlapping validities
 *  are uninterrupted, a renewal must start on the day after the end of the previous purchase.
 */
fun latestSubscription(validities: Iterable<YearMonthDayRange>): YearMonthDayRange? {
    val sorted = validities.sortedByDescending { it.end }
    val latest = sorted.firstOrNull() ?: return null

    var start = latest.start
    for (validity in sorted.drop(1)) {
        if (validity.end.add(0, 0, 1) < start) {
            break
        }
        if (validity.start < start) {
            start = validity.start
        }
    }
    return YearMonthDayRange(start, latest.end)
}
