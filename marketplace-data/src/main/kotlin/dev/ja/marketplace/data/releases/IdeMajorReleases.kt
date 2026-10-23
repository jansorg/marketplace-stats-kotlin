/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.releases

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.services.ProductRelease
import dev.ja.marketplace.services.ProductReleaseType
import kotlinx.datetime.toJavaLocalDate

/**
 * Major releases of a JetBrains IDE, e.g. 2026.1.
 */
object IdeMajorReleases {
    /**
     * @return The first release of each major version, sorted by date.
     */
    fun find(releases: Iterable<ProductRelease>): List<MajorRelease> {
        return releases
            .filter { it.type == ProductReleaseType.Release }
            .groupBy { it.majorVersion }
            .map { (majorVersion, sameMajor) ->
                MajorRelease(YearMonthDay.of(sameMajor.minOf { it.date }.toJavaLocalDate()), majorVersion)
            }
            .sortedBy { it.date }
    }
}
