/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.trackers

import dev.ja.marketplace.client.model.LicensePeriod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

class RecurringRevenueSalesTest {
    @Test
    fun monthlyFactor() {
        assertEquals(BigDecimal.ONE, RecurringRevenueSales.monthlyFactor(LicensePeriod.Monthly))
        assertEquals(BigDecimal("10.00"), (BigDecimal(120) * RecurringRevenueSales.monthlyFactor(LicensePeriod.Annual)).setScale(2, RoundingMode.HALF_UP))
        assertEquals(BigDecimal.ONE, RecurringRevenueSales.monthlyFactor(LicensePeriod.Perpetual))
    }

    @Test
    fun annualFactor() {
        assertEquals(BigDecimal(12), RecurringRevenueSales.annualFactor(LicensePeriod.Monthly))
        assertEquals(BigDecimal.ONE, RecurringRevenueSales.annualFactor(LicensePeriod.Annual))
        assertEquals(BigDecimal.ONE, RecurringRevenueSales.annualFactor(LicensePeriod.Perpetual))
    }
}
