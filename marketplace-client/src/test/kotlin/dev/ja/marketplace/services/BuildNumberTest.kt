/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BuildNumberTest {
    @Test
    fun parse() {
        assertEquals(BuildNumber(intArrayOf(1, 2, 3)), BuildNumber.of("1.2.3"))
        assertEquals(BuildNumber(intArrayOf(221, 123)), BuildNumber.of("221.123"))
        assertEquals(BuildNumber(intArrayOf(162, 1236)), BuildNumber.of("162.1236"))
        // a build with a suffix
        assertEquals(BuildNumber(intArrayOf(251)), BuildNumber.of("251.SNAPSHOT"))
    }

    @Test
    fun compare() {
        // a build with more segments is later, in both directions
        assertEquals(-1, BuildNumber.of("251").compareTo(BuildNumber.of("251.1")))
        assertEquals(1, BuildNumber.of("251.1").compareTo(BuildNumber.of("251")))
        assertEquals(0, BuildNumber.of("251.1").compareTo(BuildNumber.of("251.1")))
        assertEquals(listOf("243.2", "251", "251.1").map(BuildNumber::of), listOf("251.1", "251", "243.2").map(BuildNumber::of).sorted())
    }
}
