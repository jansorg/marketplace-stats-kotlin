/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.SalesGenerator
import dev.ja.marketplace.client.model.LicensePeriod
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.data.ContinuityDiscount
import dev.ja.marketplace.client.LicenseInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BaseContinuityDiscountTrackerTest {
    @Test
    fun basics() {
        val tracker = BaseContinuityDiscountTracker()
        assertEquals(ContinuityDiscount.FirstYear, tracker.nextContinuity("any", YearMonthDay.now()))

        val validity = YearMonthDayRange(YearMonthDay(2024, 6, 1), YearMonthDay(2024, 6, 30))

        val sale = SalesGenerator.createSale(validity = validity, type = LicensePeriod.Monthly)
        val license = LicenseInfo.createFrom(listOf(sale)).first()
        tracker.process(license)

        // 2nd and 3rd year
        assertEquals(ContinuityDiscount.FirstYear, tracker.nextContinuity(license.id, validity.start))
        assertEquals(ContinuityDiscount.FirstYear, tracker.nextContinuity(license.id, validity.end))
        assertEquals(ContinuityDiscount.FirstYear, tracker.nextContinuity(license.id, validity.end.add(0, 11, 0)))

        assertEquals(ContinuityDiscount.SecondYear, tracker.nextContinuity(license.id, validity.end.add(0, 12, 0)))
        assertEquals(ContinuityDiscount.SecondYear, tracker.nextContinuity(license.id, validity.end.add(0, 13, 0)))
        assertEquals(ContinuityDiscount.SecondYear, tracker.nextContinuity(license.id, validity.end.add(0, 23, 0)))

        assertEquals(ContinuityDiscount.ThirdYear, tracker.nextContinuity(license.id, validity.end.add(0, 24, 0)))
        assertEquals(ContinuityDiscount.ThirdYear, tracker.nextContinuity(license.id, validity.end.add(0, 25, 0)))
    }

    @Test
    fun repurchasedLicense() {
        val tracker = BaseContinuityDiscountTracker()

        // the same license was purchased as a new license twice, years apart
        val first = SalesGenerator.createSale(validity = YearMonthDay(2020, 1, 1).rangeTo(YearMonthDay(2020, 12, 31)))
        val firstLicense = LicenseInfo.createFrom(listOf(first)).single()
        val second = SalesGenerator.createSale(validity = YearMonthDay(2023, 1, 1).rangeTo(YearMonthDay(2023, 12, 31))).let { sale ->
            val item = sale.lineItems.single() as dev.ja.marketplace.client.model.SubscriptionPluginSaleItem
            sale.copy(lineItems = listOf(item.copy(licenseIds = listOf(firstLicense.id))))
        }
        val licenses = LicenseInfo.createFrom(listOf(second, first))
        assertEquals(listOf(first.ref, second.ref), licenses.map { it.sale.ref }, "licenses must be sorted by validity")
        licenses.forEach(tracker::process)

        // continuity must be based on the latest new sale, i.e. 17 months after the 2nd purchase
        // and not 53 months after the 1st purchase
        assertEquals(ContinuityDiscount.SecondYear, tracker.nextContinuity(firstLicense.id, YearMonthDay(2024, 6, 1)))
    }
}
