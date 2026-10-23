/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.releases

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.services.ProductRelease
import dev.ja.marketplace.services.ProductReleaseType
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdeMajorReleasesTest {
    private fun release(date: String, majorVersion: String, version: String, type: ProductReleaseType = ProductReleaseType.Release) =
        ProductRelease(date = LocalDate.parse(date), type = type, majorVersion = majorVersion, version = version, patches = emptyMap())

    @Test
    fun firstReleaseOfEachMajorVersion() {
        val releases = listOf(
            release("2026-08-12", "2026.1", "2026.1.3"),
            release("2026-07-16", "2026.2", "2026.2"),
            release("2026-03-25", "2026.1", "2026.1"),
            release("2026-03-01", "2026.1", "2026.1-eap", ProductReleaseType.EAP),
            release("2025-12-08", "2025.3", "2025.3"),
        )
        assertEquals(
            listOf(
                MajorRelease(YearMonthDay(2025, 12, 8), "2025.3"),
                MajorRelease(YearMonthDay(2026, 3, 25), "2026.1"),
                MajorRelease(YearMonthDay(2026, 7, 16), "2026.2"),
            ),
            IdeMajorReleases.find(releases)
        )
    }
}
