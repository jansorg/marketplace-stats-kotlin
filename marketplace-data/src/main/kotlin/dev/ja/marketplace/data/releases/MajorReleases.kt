/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.releases

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.PluginReleaseInfo

/**
 * The first release of a new major version of a paid or freemium plugin.
 *
 * @param version Version of the release, shortened to the major and minor version, e.g. `2.0`.
 */
data class MajorRelease(val date: YearMonthDay, val version: String)

object MajorReleases {
    private val preReleasePattern = Regex("(alpha|beta|eap|rc|snapshot|preview)", RegexOption.IGNORE_CASE)

    /**
     * Finds the first release of each new major version.
     *
     * The major version is the first number of the release version of a paid or freemium plugin.
     * Releases of an older major version, e.g. maintenance releases after a new major version, are ignored.
     * Pre-releases like betas are only used if there's no other release of the major version.
     */
    fun find(releases: Iterable<PluginReleaseInfo>): List<MajorRelease> {
        val byMajorVersion = releases
            .filter { it.listed && !it.hidden }
            .mapNotNull { release -> majorVersion(release.releaseVersion)?.let { it to release } }
            .groupBy({ it.first }, { it.second })

        var latestMajorRelease: PluginReleaseInfo? = null
        val result = mutableListOf<MajorRelease>()
        for (major in byMajorVersion.keys.sorted()) {
            val sameMajor = byMajorVersion.getValue(major).sortedBy { it.createdTimestamp }
            val first = sameMajor.firstOrNull { !isPreRelease(it.version) } ?: sameMajor.first()
            // a new major version must be released after the previous major version
            if (latestMajorRelease != null && first.createdTimestamp < latestMajorRelease.createdTimestamp) {
                continue
            }
            latestMajorRelease = first
            result += MajorRelease(YearMonthDay.of(first.createdTimestamp), shortVersion(first.version))
        }
        return result
    }

    private fun majorVersion(releaseVersion: String?): Int? {
        return releaseVersion?.substringBefore('.')?.toIntOrNull()
    }

    private fun isPreRelease(version: String): Boolean {
        return preReleasePattern.containsMatchIn(version)
    }

    private fun shortVersion(version: String): String {
        return version.split('.', '-').take(2).joinToString(".")
    }
}
