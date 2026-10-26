/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginDataVersionTest {
    @Test
    fun sameDataIsTheSameVersion() {
        val sales = listOf("a", "b")
        assertTrue(PluginDataVersion(listOf(sales, null, 1L)).isSameAs(PluginDataVersion(listOf(sales, null, 1L))))
        // data, which is assembled for each request, e.g. the trials without a persistent cache, has the same content
        assertTrue(PluginDataVersion(listOf(listOf("a", "b"))).isSameAs(PluginDataVersion(listOf(listOf("a", "b")))))
    }

    @Test
    fun newDataIsAnotherVersion() {
        assertFalse(PluginDataVersion(listOf(listOf("a"), 1L)).isSameAs(PluginDataVersion(listOf(listOf("a", "b"), 1L))))
        assertFalse(PluginDataVersion(listOf(listOf("a"), 1L)).isSameAs(PluginDataVersion(listOf(listOf("a"), 2L))))
        assertFalse(PluginDataVersion(listOf(listOf("a"), null)).isSameAs(PluginDataVersion(listOf(listOf("a"), Any()))))
        assertFalse(PluginDataVersion(listOf("a")).isSameAs(PluginDataVersion(listOf("a", "b"))))
        // objects without equals, e.g. exchange rates, are only the same object
        assertFalse(PluginDataVersion(listOf(Any())).isSameAs(PluginDataVersion(listOf(Any()))))
    }
}
