/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Decodes the values of an enum of the API by their [apiValue]. A value, which the client doesn't know, e.g. a new type of
 * sale, is decoded as [unknown] and logged once, so that one new value doesn't fail a whole response, e.g. a month of sales.
 */
open class TolerantEnumSerializer<T : Enum<T>>(
    serialName: String,
    private val values: List<T>,
    private val unknown: T,
    private val apiValue: (T) -> String,
) : KSerializer<T> {
    private val byApiValue = values.filter { it != unknown }.associateBy(apiValue)
    private val loggedValues = ConcurrentHashMap.newKeySet<String>()

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): T {
        val value = decoder.decodeString()
        return byApiValue[value] ?: unknown.also {
            if (loggedValues.add(value)) {
                logger.warn("Unknown value \"$value\" of ${descriptor.serialName}, it's handled as ${unknown.name}")
            }
        }
    }

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(apiValue(value))
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(TolerantEnumSerializer::class.java)
    }
}
