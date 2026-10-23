/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.releases

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.PluginReleaseInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class MajorReleasesTest {
    private var nextId = 1

    private fun release(date: String, version: String, releaseVersion: String?, listed: Boolean = true): PluginReleaseInfo {
        return PluginReleaseInfo(
            id = nextId++,
            pluginId = 1,
            updateInfoUrlPath = "",
            version = version,
            approve = true,
            listed = listed,
            hidden = false,
            createdTimestamp = Instant.parse("${date}T12:00:00Z"),
            fileUrlPath = "",
            fileSizeBytes = 0,
            channel = "",
            downloads = 0,
            releaseVersion = releaseVersion,
        )
    }

    @Test
    fun majorReleases() {
        val releases = listOf(
            release("2023-06-28", "0.0.2.0-232-EAP", null),
            release("2024-04-24", "1.0.0.345-233", "1.0"),
            release("2024-05-01", "1.1.0.350-233", "1.1"),
            // pre-release of 2.0 before the final release
            release("2026-05-01", "2.0.0-beta1", "2.0"),
            release("2026-05-19", "2.0.0.457-262", "2.0"),
            // maintenance release of the previous major version
            release("2026-05-20", "1.5.20", "1.0"),
            release("2026-08-04", "3.0.1.502-253", "3.0"),
            // unlisted releases are ignored
            release("2026-09-01", "4.0.0", "4.0", listed = false),
        )

        assertEquals(
            listOf(
                MajorRelease(YearMonthDay(2024, 4, 24), "1.0"),
                MajorRelease(YearMonthDay(2026, 5, 19), "2.0"),
                MajorRelease(YearMonthDay(2026, 8, 4), "3.0"),
            ),
            MajorReleases.find(releases)
        )
    }

    @Test
    fun onlyPreReleases() {
        val releases = listOf(
            release("2025-07-07", "5.0.0.252-beta5", "50.0"),
            release("2025-07-20", "5.0.0.252-beta6", "50.0"),
        )
        assertEquals(listOf(MajorRelease(YearMonthDay(2025, 7, 7), "5.0")), MajorReleases.find(releases))
    }
}
