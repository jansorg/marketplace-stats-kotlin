/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@OptIn(ExperimentalSerializationApi::class)
object YearMonthDateSerializer : KSerializer<YearMonthDay> {
    override fun deserialize(decoder: Decoder): YearMonthDay {
        decoder as JsonDecoder

        val values = decoder.decodeJsonElement().jsonArray
        return YearMonthDay(
            values[0].jsonPrimitive.int,
            values[1].jsonPrimitive.int,
            values[2].jsonPrimitive.int,
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

object AmountSerializer : KSerializer<Amount> {
    override fun deserialize(decoder: Decoder): Amount {
        return decoder.decodeString().toBigDecimal()
    }

    override fun serialize(encoder: Encoder, value: Amount) {
        encoder.encodeString(value.toPlainString())
    }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)
}

object CDateSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant {
        val millis = decoder.decodeString().toLong()
        return Instant.fromEpochMilliseconds(millis)
    }

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toEpochMilliseconds().toString())
    }
}

class NullableStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("NullableString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        return String.serializer().nullable.deserialize(decoder)?.takeUnless(String::isEmpty)
    }

    override fun serialize(encoder: Encoder, value: String?) {
        return String.serializer().nullable.serialize(encoder, value)
    }
}
