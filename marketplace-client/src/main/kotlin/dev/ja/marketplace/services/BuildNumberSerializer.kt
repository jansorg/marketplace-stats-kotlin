/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class BuildNumberSerializer : KSerializer<BuildNumber> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("buildNumber", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BuildNumber {
        return BuildNumber.of(decoder.decodeString())
    }

    override fun serialize(encoder: Encoder, value: BuildNumber) {
        encoder.encodeString(value.toBuilderNumberString())
    }
}
