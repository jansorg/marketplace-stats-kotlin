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

object ProductDownloadTypeSerializer : KSerializer<ProductDownloadType> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("productDownloadType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ProductDownloadType {
        return ProductDownloadType.findById(decoder.decodeString())
    }

    override fun serialize(encoder: Encoder, value: ProductDownloadType) {
        encoder.encodeString(value.id)
    }
}