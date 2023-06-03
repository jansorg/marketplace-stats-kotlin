/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import java.math.BigDecimal

@Suppress("OPT_IN_USAGE")
class YearMonthDateSerializer : KSerializer<YearMonthDay> {
    override fun deserialize(decoder: Decoder): YearMonthDay {
        decoder as JsonDecoder

        val values = decoder.decodeJsonElement().jsonArray
        return YearMonthDay(
            values[0].jsonPrimitive.int,
            values[1].jsonPrimitive.int,
            values[2].jsonPrimitive.int
        )
    }

    override fun serialize(encoder: Encoder, value: YearMonthDay) {
        encoder as JsonEncoder
        encoder.encodeJsonElement(buildJsonArray {
            add(value.year)
            add(value.month)
            add(value.day)
        })
    }

    override val descriptor: SerialDescriptor = SerialDescriptor(
        "marketplace.yearMonthDate",
        IntArraySerializer().descriptor
    )
}

object AmountSerializer : KSerializer<BigDecimal> {
    override fun deserialize(decoder: Decoder): BigDecimal {
        return decoder.decodeString().toBigDecimal()
    }

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toPlainString())
    }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)
}