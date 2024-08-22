/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.Serializable

@Serializable(BuildNumberSerializer::class)
data class BuildNumber(private val segments: IntArray) : Comparable<BuildNumber> {
    val baseVersion: Int
        get() {
            return segments[0]
        }

    fun toBuilderNumberString(): String {
        return segments.joinToString(".")
    }

    override fun compareTo(other: BuildNumber): Int {
        val otherSize = other.segments.size
        segments.forEachIndexed { index, a ->
            if (index >= otherSize) {
                return 1
            }

            val b = other.segments[index]
            if (a > b) {
                return 1
            }

            if (a < b) {
                return -1
            }
        }

        return 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BuildNumber

        return segments.contentEquals(other.segments)
    }

    override fun hashCode(): Int {
        return segments.contentHashCode()
    }

    companion object {
        fun of(value: String): BuildNumber {
            val items = value.split('.').map(String::toInt)
            require(items.isNotEmpty()) {
                "Unable to parse build number $value"
            }
            return BuildNumber(items.toIntArray())
        }
    }
}