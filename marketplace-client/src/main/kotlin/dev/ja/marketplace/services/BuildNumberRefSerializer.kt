/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object BuildNumberRefSerializer : KSerializer<BuildNumberRef?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("buildNumberRef", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BuildNumberRef? {
        val value = decoder.decodeString()
        if (value.isEmpty()) {
            return null
        }
        return BuildNumberRef.of(value)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: BuildNumberRef?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.toBuilderNumberString())
        }
    }
}
