/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.*
import java.math.BigDecimal
import javax.money.MonetaryAmount
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

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

/**
 * A date as array like `[2026, 3, 1]` or as ISO date like `2026-03-01`, also with a time. The documentation of the Check
 * License API shows both formats.
 */
object LenientDateSerializer : KSerializer<YearMonthDay> {
    override fun deserialize(decoder: Decoder): YearMonthDay {
        decoder as JsonDecoder
        return when (val element = decoder.decodeJsonElement()) {
            is JsonArray -> YearMonthDay(element[0].jsonPrimitive.int, element[1].jsonPrimitive.int, element[2].jsonPrimitive.int)
            else -> YearMonthDay.parse(element.jsonPrimitive.content.take(10))
        }
    }

    override fun serialize(encoder: Encoder, value: YearMonthDay) {
        encoder.encodeString(value.asIsoString)
    }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("marketplace.lenientDate", PrimitiveKind.STRING)
}

object BigDecimalSerializer : KSerializer<BigDecimal> {
    override fun deserialize(decoder: Decoder): BigDecimal {
        return decoder.decodeDouble().toBigDecimal()
    }

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeDouble(value.toDouble())
    }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("amount", PrimitiveKind.DOUBLE)
}

object MonetaryAmountUsdSerializer : KSerializer<MonetaryAmount> {
    override fun deserialize(decoder: Decoder): MonetaryAmount {
        return fastMoneyOf(decoder.decodeDouble(), "USD")
    }

    override fun serialize(encoder: Encoder, value: MonetaryAmount) {
        encoder.encodeDouble(value.number.doubleValueExact())
    }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("amount", PrimitiveKind.DOUBLE)
}

@OptIn(ExperimentalTime::class)
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


object PluginSaleSerializer : KSerializer<PluginSale> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("pluginSale") {
        element<String>("ref")
        element<YearMonthDay>("date")
        element<Double>("amount")
        element<String>("currency")
        element<Double>("amountUSD")
        element<LicensePeriod>("period")
        element<CustomerInfo>("customer")
        element<ResellerInfo?>("reseller")
        element<List<JsonPluginSaleItem>>("lineItems")
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): PluginSale {
        return decoder.decodeStructure(descriptor) {
            var ref: String? = null
            var date: YearMonthDay? = null
            var amountValue: Double? = null
            var currency: String? = null
            var amountValueUSD: Double? = null
            var period: LicensePeriod? = null
            var customer: CustomerInfo? = null
            var reseller: ResellerInfo? = null
            var lineItems: List<JsonPluginSaleItem>? = null

            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> ref = decodeStringElement(descriptor, index)
                    1 -> date = decodeSerializableElement(descriptor, index, YearMonthDay.serializer())
                    2 -> amountValue = decodeDoubleElement(descriptor, index)
                    3 -> currency = decodeStringElement(descriptor, index)
                    4 -> amountValueUSD = decodeDoubleElement(descriptor, index)
                    5 -> period = decodeSerializableElement(descriptor, index, LicensePeriod.serializer().nullable)
                    6 -> customer = decodeNullableSerializableElement(descriptor, index, CustomerInfo.serializer())
                    7 -> reseller = decodeNullableSerializableElement(descriptor, index, ResellerInfo.serializer())
                    8 -> lineItems = decodeNullableSerializableElement(descriptor, index, ListSerializer(JsonPluginSaleItem.serializer()))
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("Unexpected index: $index")
                }
            }
            require(ref != null && date != null && amountValue != null && currency != null && amountValueUSD != null && customer != null)
            require(lineItems != null)

            val amountCurrencyUnit = MarketplaceCurrencies.of(currency)
            val amountUSD = fastMoneyOf(amountValueUSD, MarketplaceCurrencies.USD.currencyCode)
            val amount = when {
                currency == "USD" -> amountUSD
                else -> fastMoneyOf(amountValue, amountCurrencyUnit.currencyCode)
            }

            PluginSale(
                ref,
                date,
                amount,
                amountUSD,
                period ?: LicensePeriod.Perpetual,
                customer,
                reseller,
                lineItems.map {
                    when {
                        it.subscriptionDates == null -> PerpetualPluginSaleItem(
                            it.type,
                            it.licenseIds,
                            fastMoneyOf(it.amount, amountCurrencyUnit.currencyCode),
                            fastMoneyOf(it.amountUSD, MarketplaceCurrencies.USD.currencyCode),
                            it.discountDescriptions
                        )

                        else -> SubscriptionPluginSaleItem(
                            it.type,
                            it.licenseIds,
                            fastMoneyOf(it.amount, amountCurrencyUnit.currencyCode),
                            fastMoneyOf(it.amountUSD, MarketplaceCurrencies.USD.currencyCode),
                            it.discountDescriptions,
                            it.subscriptionDates
                        )
                    }
                }
            )
        }
    }

    override fun serialize(encoder: Encoder, value: PluginSale) {
        TODO("not yet implemented")
    }
}
/**
 * The ID of a plugin, which is stored as number, e.g. `"pluginId": 123`. The API and older stored data have the plugin
 * as object with its ID, name and link, e.g. `"plugin": {"id": 123, "name": "Plugin", "link": "/plugin/123"}`, only
 * its ID is decoded. The property needs the alternative name of the object, e.g. `@JsonNames("plugin")`.
 */
object PluginIdOrInfoSerializer : KSerializer<PluginId> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("marketplace.pluginIdOrInfo", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): PluginId {
        if (decoder !is JsonDecoder) {
            return decoder.decodeInt()
        }

        return when (val element = decoder.decodeJsonElement()) {
            is JsonPrimitive -> element.int
            is JsonObject -> element["id"]?.jsonPrimitive?.int ?: throw SerializationException("Plugin without ID: $element")
            else -> throw SerializationException("Unexpected plugin: $element")
        }
    }

    override fun serialize(encoder: Encoder, value: PluginId) {
        encoder.encodeInt(value)
    }
}
