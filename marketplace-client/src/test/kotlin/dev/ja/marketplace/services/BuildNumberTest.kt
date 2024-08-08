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
    }
}