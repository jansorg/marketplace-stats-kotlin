/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.Serializable

/**
 * A reference to a product [BuildNumber], e.g. a 'since' or 'until' attribute value.
 * This supports the wildcard qualifier
 */
@Serializable(BuildNumberRefSerializer::class)
data class BuildNumberRef(private val segments: IntArray, val hasWildcard: Boolean) : Comparable<BuildNumber> {
    fun toBuilderNumberString(): String {
        val base = segments.joinToString(".")
        return when {
            hasWildcard -> "$base.*"
            else -> base
        }
    }

    override fun compareTo(other: BuildNumber): Int {
        val buildSegments = other.segments
        segments.forEachIndexed { index, a ->
            if (index >= buildSegments.size) {
                return 1
            }

            val b = buildSegments[index]
            if (a > b) {
                return 1
            }

            if (a < b) {
                return -1
            }
        }

        if (hasWildcard) {
            return 1
        }

        return 0
    }

    /**
     * A number, which sorts the references by their builds, e.g. in sortable tables: the first [SORTED_SEGMENTS]
     * segments, a wildcard sorts after all builds it matches, e.g. `202.*` after `202.123` and `202.0`.
     */
    val sortValue: Long
        get() {
            var value = 0L
            for (index in 0 until SORTED_SEGMENTS) {
                val segment = when {
                    index < segments.size -> segments[index].coerceIn(0, if (index == 0) MAX_FIRST_SEGMENT else MAX_SEGMENT)
                    hasWildcard -> MAX_SEGMENT
                    else -> 0
                }
                value = value * (MAX_SEGMENT + 1) + segment
            }
            return value
        }

    infix fun equalsBuild(other: BuildNumber): Boolean {
        return compareTo(other) == 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BuildNumberRef

        return segments.contentEquals(other.segments) && hasWildcard == other.hasWildcard
    }

    override fun hashCode(): Int {
        return segments.contentHashCode()
    }

    override fun toString(): String {
        return toBuilderNumberString()
    }

    companion object {
        private const val SORTED_SEGMENTS = 4
        private const val MAX_SEGMENT = 99_999

        /** The first segment is the major version, e.g. 263, the larger values wouldn't fit into a [Long]. */
        private const val MAX_FIRST_SEGMENT = 9_000

        fun of(value: String): BuildNumberRef {
            val segments = value
                .let {
                    // some older plugins use a value like "IC-193.0", we're parsing this to 193.*
                    when (val index = value.indexOf('-')) {
                        -1 -> it
                        else -> it.substring(index + 1)
                    }
                }
                .removeSuffix(".*")
                .split('.')
                .mapNotNull(String::toIntOrNull)

            require(segments.isNotEmpty()) {
                "Unable to parse build number $value"
            }

            return BuildNumberRef(segments.toIntArray(), value.endsWith(".*"))
        }
    }
}