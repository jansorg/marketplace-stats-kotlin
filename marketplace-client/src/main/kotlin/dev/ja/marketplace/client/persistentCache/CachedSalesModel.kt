/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

@file:OptIn(ExperimentalSerializationApi::class)

package dev.ja.marketplace.client.persistentCache

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.fastMoneyOf
import dev.ja.marketplace.client.model.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.time.LocalDate
import javax.money.MonetaryAmount

/*
 * The cache files don't use the serializers of the API model, because they are bound to JSON and the API format.
 * The classes in this file define the stored format. Enums are stored with explicit codes,
 * so that renaming or reordering an enum of the API model doesn't silently change the meaning of stored data.
 *
 * Increment the data version of the cache file (see HistoryKind) after incompatible changes.
 */

/**
 * Stored history of sales or trials of a single plugin.
 *
 * @param fetchedUntil The end date of the latest fetch. Items until this date are complete,
 *  except for changes, which are only found by fetching them again.
 * @param lastFetchMillis Time of the latest fetch, in milliseconds since the epoch.
 * @param lastFullFetchMillis Time of the latest fetch of the complete history, in milliseconds since the epoch.
 */
@Serializable
internal class CachedHistory<T>(
    @ProtoNumber(1) val fetchedUntil: Int,
    @ProtoNumber(2) val lastFetchMillis: Long,
    @ProtoNumber(3) val lastFullFetchMillis: Long,
    @ProtoNumber(4) val items: List<T> = emptyList(),
)

@Serializable
internal class CachedSale(
    @ProtoNumber(1) val ref: String,
    @ProtoNumber(2) val date: Int,
    @ProtoNumber(3) val currency: String,
    @ProtoNumber(4) val amount: Double,
    @ProtoNumber(5) val amountUSD: Double,
    @ProtoNumber(6) val licensePeriod: Int,
    @ProtoNumber(7) val customer: CachedCustomer,
    @ProtoNumber(8) val reseller: CachedReseller? = null,
    @ProtoNumber(9) val lineItems: List<CachedSaleItem> = emptyList(),
)

@Serializable
internal class CachedSaleItem(
    @ProtoNumber(1) val type: Int,
    @ProtoNumber(2) val licenseIds: List<String> = emptyList(),
    @ProtoNumber(3) val amount: Double,
    @ProtoNumber(4) val amountUSD: Double,
    @ProtoNumber(5) val discounts: List<CachedDiscount> = emptyList(),
    // both null for perpetual licenses
    @ProtoNumber(6) val subscriptionStart: Int? = null,
    @ProtoNumber(7) val subscriptionEnd: Int? = null,
)

@Serializable
internal class CachedDiscount(
    @ProtoNumber(1) val description: String,
    @ProtoNumber(2) val percent: Double? = null,
)

@Serializable
internal class CachedCustomer(
    @ProtoNumber(1) val code: Int,
    @ProtoNumber(2) val country: String,
    @ProtoNumber(3) val type: Int,
    @ProtoNumber(4) val name: String? = null,
)

@Serializable
internal class CachedReseller(
    @ProtoNumber(1) val code: Int,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val country: String,
    @ProtoNumber(4) val type: Int,
)

@Serializable
internal class CachedTrial(
    @ProtoNumber(1) val ref: String,
    @ProtoNumber(2) val date: Int,
    @ProtoNumber(3) val customer: CachedCustomer,
)

internal fun YearMonthDay.toCached(): Int = sortValue.toInt()

internal fun cachedDate(epochDay: Int): YearMonthDay = YearMonthDay.of(LocalDate.ofEpochDay(epochDay.toLong()))

private fun MonetaryAmount.toCached(): Double = number.doubleValueExact()

internal fun PluginSale.toCached(): CachedSale = CachedSale(
    ref = ref,
    date = date.toCached(),
    currency = amount.currency.currencyCode,
    amount = amount.toCached(),
    amountUSD = amountUSD.toCached(),
    licensePeriod = licensePeriod.toCached(),
    customer = customer.toCached(),
    reseller = reseller?.toCached(),
    lineItems = lineItems.map(PluginSaleItem::toCached),
)

internal fun CachedSale.toModel(): PluginSale {
    val amountUSD = fastMoneyOf(amountUSD, "USD")
    return PluginSale(
        ref = ref,
        date = cachedDate(date),
        amount = if (currency == "USD") amountUSD else fastMoneyOf(amount, currency),
        amountUSD = amountUSD,
        licensePeriod = licensePeriodOf(licensePeriod),
        customer = customer.toModel(),
        reseller = reseller?.toModel(),
        lineItems = lineItems.map { it.toModel(currency) },
    )
}

private fun PluginSaleItem.toCached(): CachedSaleItem {
    val subscriptionDates = (this as? SubscriptionPluginSaleItem)?.subscriptionDates
    return CachedSaleItem(
        type = type.toCached(),
        licenseIds = licenseIds,
        amount = amount.toCached(),
        amountUSD = amountUSD.toCached(),
        discounts = discountDescriptions.map { CachedDiscount(it.description, it.percent) },
        subscriptionStart = subscriptionDates?.start?.toCached(),
        subscriptionEnd = subscriptionDates?.end?.toCached(),
    )
}

private fun CachedSaleItem.toModel(currency: String): PluginSaleItem {
    val type = saleItemTypeOf(type)
    val amount = fastMoneyOf(amount, currency)
    val amountUSD = fastMoneyOf(amountUSD, "USD")
    val discounts = discounts.map { PluginSaleItemDiscount(it.description, it.percent) }
    return when {
        subscriptionStart != null && subscriptionEnd != null -> SubscriptionPluginSaleItem(
            type, licenseIds, amount, amountUSD, discounts,
            YearMonthDayRange(cachedDate(subscriptionStart), cachedDate(subscriptionEnd))
        )

        else -> PerpetualPluginSaleItem(type, licenseIds, amount, amountUSD, discounts)
    }
}

internal fun PluginTrial.toCached(): CachedTrial = CachedTrial(referenceId, date.toCached(), customer.toCached())

internal fun CachedTrial.toModel(): PluginTrial = PluginTrial(ref, cachedDate(date), customer.toModel())

private fun CustomerInfo.toCached(): CachedCustomer = CachedCustomer(code, country, type.toCached(), name)

private fun CachedCustomer.toModel(): CustomerInfo = CustomerInfo(code, country, customerTypeOf(type), name)

private fun ResellerInfo.toCached(): CachedReseller = CachedReseller(code, name, country, type.toCached())

private fun CachedReseller.toModel(): ResellerInfo = ResellerInfo(code, name, country, resellerTypeOf(type))

private fun LicensePeriod.toCached(): Int = when (this) {
    LicensePeriod.Annual -> 1
    LicensePeriod.Monthly -> 2
    LicensePeriod.Perpetual -> 3
}

private fun licensePeriodOf(code: Int): LicensePeriod = when (code) {
    1 -> LicensePeriod.Annual
    2 -> LicensePeriod.Monthly
    3 -> LicensePeriod.Perpetual
    else -> throw IllegalStateException("Unknown license period code $code")
}

private fun CustomerType.toCached(): Int = when (this) {
    CustomerType.Organization -> 1
    CustomerType.Individual -> 2
}

private fun customerTypeOf(code: Int): CustomerType = when (code) {
    1 -> CustomerType.Organization
    2 -> CustomerType.Individual
    else -> throw IllegalStateException("Unknown customer type code $code")
}

private fun ResellerType.toCached(): Int = when (this) {
    ResellerType.Reseller -> 1
    ResellerType.Organization -> 2
}

private fun resellerTypeOf(code: Int): ResellerType = when (code) {
    1 -> ResellerType.Reseller
    2 -> ResellerType.Organization
    else -> throw IllegalStateException("Unknown reseller type code $code")
}

private fun PluginSaleItemType.toCached(): Int = when (this) {
    PluginSaleItemType.New -> 1
    PluginSaleItemType.Renew -> 2
}

private fun saleItemTypeOf(code: Int): PluginSaleItemType = when (code) {
    1 -> PluginSaleItemType.New
    2 -> PluginSaleItemType.Renew
    else -> throw IllegalStateException("Unknown sale item type code $code")
}
