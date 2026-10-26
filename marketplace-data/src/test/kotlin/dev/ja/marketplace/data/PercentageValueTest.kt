/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

class PercentageValueTest {
    @Test
    fun rates() {
        assertEquals(BigDecimal("66.67"), PercentageValue.rateOf(2, 3)!!.value.setScale(2, RoundingMode.HALF_UP))
        // nothing to compare
        assertNull(PercentageValue.rateOf(0, 0))
        // a rate of zero is 0 %, not the value, which is shown as "no value"
        val zero = PercentageValue.rateOf(0, 3)!!
        assertEquals(0, zero.value.signum())
        assertNotSame(PercentageValue.ZERO, zero)
        // shares of the tables use ZERO
        assertSame(PercentageValue.ZERO, PercentageValue.of(0, 3))
    }
}
