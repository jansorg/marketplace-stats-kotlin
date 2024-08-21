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

object ProductCategorySerializer : KSerializer<ProductCategory> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("productCategory", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ProductCategory {
        return ProductCategory.fingById(decoder.decodeString())
    }

    override fun serialize(encoder: Encoder, value: ProductCategory) {
        encoder.encodeString(value.id)
    }
}
