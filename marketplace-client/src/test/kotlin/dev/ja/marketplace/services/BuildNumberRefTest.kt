/*
 * Copyright (c) 2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BuildNumberRefTest {
    @Test
    fun parse() {
        assertEquals(BuildNumberRef(intArrayOf(1, 2, 3), false), BuildNumberRef.of("1.2.3"))
        assertEquals(BuildNumberRef(intArrayOf(1, 2), true), BuildNumberRef.of("1.2.*"))
        assertEquals(BuildNumberRef(intArrayOf(221, 123), false), BuildNumberRef.of("221.123"))
        assertEquals(BuildNumberRef(intArrayOf(162, 1236), true), BuildNumberRef.of("162.1236.*"))

        assertEquals(BuildNumberRef(intArrayOf(193, 0), false), BuildNumberRef.of("IC-193.0"))
    }

    @Test
    fun compare() {
        assertTrue(BuildNumberRef.of("242.0") <= BuildNumber.of("242.10241.18"))
        assertTrue(BuildNumberRef.of("242.*") > BuildNumber.of("242.10241.18"))
        assertTrue(BuildNumberRef.of("242.*") >= BuildNumber.of("242.10241.18"))
        assertTrue(BuildNumberRef.of("243.0") > BuildNumber.of("242.10241.18"))
        assertTrue(BuildNumberRef.of("243.0") >= BuildNumber.of("242.10241.18"))
        assertTrue(BuildNumberRef.of("242.0") equalsBuild BuildNumber.of("242.0"))

        assertFalse(BuildNumberRef.of("242.*") < BuildNumber.of("242.20224.300"))
        assertTrue(BuildNumberRef.of("242.*") > BuildNumber.of("242.20224.300"))
    }

    @Test
    fun sortValue() {
        // a wildcard sorts after all builds it matches, and before the next major version
        val sorted = listOf("202.0", "202.123", "202.123.4", "202.6000", "202.*", "203.0", "1000.1.2.3")
        assertEquals(sorted, sorted.reversed().sortedBy { BuildNumberRef.of(it).sortValue })
        assertEquals(BuildNumberRef.of("202").sortValue, BuildNumberRef.of("202.0").sortValue)
        assertTrue(BuildNumberRef.of("202.123.*").sortValue < BuildNumberRef.of("202.124").sortValue)
    }
}