/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.SalesGenerator
import dev.ja.marketplace.client.LicensePeriod
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.data.LicenseInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContinuityDiscountTrackerTest {
    @Test
    fun basics() {
        val tracker = ContinuityDiscountTracker()
        assertEquals(1.0, tracker.nextContinuityFactor("any", YearMonthDay.now()))

        val validity = YearMonthDayRange(YearMonthDay(2024, 6, 1), YearMonthDay(2024, 6, 30))

        val sale = SalesGenerator.createSale(validity = validity, type = LicensePeriod.Monthly)
        val license = LicenseInfo.create(listOf(sale)).first()
        tracker.process(license)

        // 2nd and 3rd year
        assertEquals(1.0, tracker.nextContinuityFactor(license.id, validity.start))
        assertEquals(1.0, tracker.nextContinuityFactor(license.id, validity.end))
        assertEquals(1.0, tracker.nextContinuityFactor(license.id, validity.end.add(0, 11, 0)))

        assertEquals(0.8, tracker.nextContinuityFactor(license.id, validity.end.add(0, 12, 0)))
        assertEquals(0.8, tracker.nextContinuityFactor(license.id, validity.end.add(0, 13, 0)))
        assertEquals(0.8, tracker.nextContinuityFactor(license.id, validity.end.add(0, 23, 0)))

        assertEquals(0.6, tracker.nextContinuityFactor(license.id, validity.end.add(0, 24, 0)))
        assertEquals(0.6, tracker.nextContinuityFactor(license.id, validity.end.add(0, 25, 0)))
    }
}