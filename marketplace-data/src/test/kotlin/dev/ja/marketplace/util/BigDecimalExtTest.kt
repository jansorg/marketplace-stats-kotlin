/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BigDecimalExtTest {
    @Test
    fun testIsZero() {
        assertTrue(BigDecimal("0").isZero())
        assertTrue(BigDecimal("0.0").isZero())
        assertTrue(BigDecimal("0.00").isZero())
        assertTrue(BigDecimal("-0.00").isZero())
        assertFalse(BigDecimal("0.01").isZero())

        assertNull((null as BigDecimal?).isZero())
    }
}