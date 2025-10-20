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
}