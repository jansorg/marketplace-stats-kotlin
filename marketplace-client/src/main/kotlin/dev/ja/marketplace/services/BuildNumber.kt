/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(BuildNumber.Serializer::class)
data class BuildNumber(internal val segments: IntArray) : Comparable<BuildNumber> {
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

        // a build with more segments is later, e.g. 251.1 after 251
        return segments.size.compareTo(otherSize)
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
        /**
         * @return The numeric segments at the start of the build, e.g. 251 of `251.SNAPSHOT`.
         */
        fun of(value: String): BuildNumber {
            val items = value.split('.').map(String::toIntOrNull).takeWhile { it != null }.filterNotNull()
            require(items.isNotEmpty()) {
                "Unable to parse build number $value"
            }
            return BuildNumber(items.toIntArray())
        }
    }

    object Serializer : KSerializer<BuildNumber> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("buildNumber", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): BuildNumber {
            return BuildNumber.of(decoder.decodeString())
        }

        override fun serialize(encoder: Encoder, value: BuildNumber) {
            encoder.encodeString(value.toBuilderNumberString())
        }
    }
}
