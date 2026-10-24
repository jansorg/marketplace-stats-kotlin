/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CacheDirectoryTest {
    private val home = Path.of("/home/user")
    private val configDirectory = Path.of("/etc/marketplace-stats")

    @Test
    fun emptyValuesDisableTheCache() {
        assertNull(cacheDirectory("", configDirectory, home))
        assertNull(cacheDirectory("  ", null, home))
    }

    @Test
    fun absolutePaths() {
        assertEquals(Path.of("/var/cache/stats"), cacheDirectory("/var/cache/stats", configDirectory, home))
        assertEquals(Path.of("/var/cache/stats"), cacheDirectory("/var/cache/../cache/stats/", null, home))
    }

    @Test
    fun homeDirectory() {
        assertEquals(Path.of("/home/user/.cache/stats"), cacheDirectory("~/.cache/stats", configDirectory, home))
        assertEquals(home, cacheDirectory("~", null, home))
        // only a leading ~ is the home directory
        assertEquals(Path.of("/etc/marketplace-stats/~cache"), cacheDirectory("~cache", configDirectory, home))
    }

    @Test
    fun relativePaths() {
        assertEquals(Path.of("/etc/marketplace-stats/cache"), cacheDirectory("cache", configDirectory, home))
        assertEquals(Path.of("/etc/cache"), cacheDirectory("../cache", configDirectory, home))
        // relative to the working directory without a base directory
        assertEquals(Path.of("cache"), cacheDirectory("./cache", null, home))
    }
}
