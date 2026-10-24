/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class McpAllowedHostTest {
    @Test
    fun hostnames() {
        assertEquals("nas", normalizeMcpAllowedHost(" NAS "))
        assertEquals("stats.example.com", normalizeMcpAllowedHost("stats.example.com"))
        assertNull(normalizeMcpAllowedHost(" "))
    }

    @Test
    fun portsAreRemoved() {
        assertEquals("nas", normalizeMcpAllowedHost("nas:8080"))
        assertEquals("192.168.1.10", normalizeMcpAllowedHost("192.168.1.10:8080"))
        assertEquals("[::1]", normalizeMcpAllowedHost("[::1]:8080"))
        assertNull(normalizeMcpAllowedHost(":8080"))
    }

    @Test
    fun ipv6AddressesAreEnclosedInBrackets() {
        assertEquals("[::1]", normalizeMcpAllowedHost("::1"))
        assertEquals("[fd00::10]", normalizeMcpAllowedHost("[FD00::10]"))
        assertEquals("[fd00::10]", normalizeMcpAllowedHost("fd00::10"))
    }
}
