/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.data.offers

import dev.ja.marketplace.client.CustomerId
import dev.ja.marketplace.client.LicenseInfo
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.model.PluginOffer
import dev.ja.marketplace.client.model.PluginOfferId
import dev.ja.marketplace.client.model.PluginSaleItemDiscount
import dev.ja.marketplace.data.trackers.MonetaryAmountTracker
import dev.ja.marketplace.exchangeRate.ExchangeRates
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import org.javamoney.moneta.FastMoney
import org.javamoney.moneta.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import javax.money.MonetaryAmount

/**
 * The discounts of the sales, which belong to an offer. The Marketplace doesn't link a sale to an offer, the discount
 * of a sale with an offer is described as `"<name of the offer>" offer`.
 */
object OfferDiscounts {
    private val offerDescription = Regex("^\"(.+)\" offer$")

    /** The special discounts of the Marketplace, e.g. for students, see [isMarketplaceSpecial]. */
    private val marketplaceSpecials = listOf(
        "student discount", "graduation discount", "startup discount", "developer recognition discount", "volume discount",
        // e.g. "50% discount for Educational organisations" and "Educational License Discount"
        "educational",
        "non-profit discount", "opensource discount", "open source discount",
    )

    /**
     * @return If the name of an offer or the description of a discount is a special discount of the Marketplace, e.g.
     *  the student, graduation, startup, developer recognition, educational, non-profit, open source or subscription
 *  volume discount.
     */
    fun isMarketplaceSpecial(name: String): Boolean = marketplaceSpecials.any { name.contains(it, ignoreCase = true) }

    /**
     * @return The name of the discount in the charts: the name of the offer, or the description of a discount without
     *  an offer, e.g. `20% continuity discount`. `null` for reseller discounts, see [OfferUsages].
     */
    fun discountName(discount: PluginSaleItemDiscount): String? {
        if (discount.isResellerDiscount) {
            return null
        }
        return (offerName(discount) ?: discount.description).trim().takeIf(String::isNotEmpty)
    }

    /** The words before the name of the plugin in the names of the specials, e.g. ` on commercial plugin`. */
    private val pluginNamePrefix = Regex("\\s+(for|on)(\\s+commercial)?(\\s+plugin)?$", RegexOption.IGNORE_CASE)

    /**
     * @return The name of a special discount of the Marketplace without the name of the plugin, e.g.
     *  `Student discount` of `Student discount for My Plugin` or `50% non-profit discount` of
     *  `50% non-profit discount on commercial plugin My Plugin`. Other names are returned unchanged.
     */
    fun withoutPluginName(name: String, pluginName: String): String {
        val trimmed = name.trim()
        if (pluginName.isBlank() || !trimmed.endsWith(pluginName.trim(), ignoreCase = true)) {
            return trimmed
        }
        val shortName = trimmed.dropLast(pluginName.trim().length).trimEnd().replace(pluginNamePrefix, "").trim()
        // a name, which only consists of the name of the plugin, is kept
        return shortName.ifEmpty { trimmed }
    }

    /** @return The name of the offer of the discount, or `null` if it's not the discount of an offer. */
    fun offerName(discount: PluginSaleItemDiscount): String? {
        return offerDescription.matchEntire(discount.description.trim())?.groupValues?.get(1)
    }

    /**
     * @return The amount, which a license lost to a discount: the amount before the discount minus the paid amount.
     *  The discounts of a sale are applied one after the other, the amount before the discount is the paid amount
     *  divided by `1 - percent / 100`. It's `null` for a discount of 100 % or an unknown percent, because the paid
     *  amount is then 0 or unknown.
     */
    fun lostAmount(paid: MonetaryAmount, percent: Double?): MonetaryAmount? {
        if (percent == null || percent <= 0.0 || percent >= 100.0) {
            return null
        }
        // Money calculates with the precision of BigDecimal, the result has 5 decimals like the converted amounts,
        // because FastMoney, e.g. of the sums, only supports 5 decimals
        val percentValue = BigDecimal.valueOf(percent)
        val lost = Money.from(paid).multiply(percentValue).divide(BigDecimal(100).subtract(percentValue))
        return FastMoney.of(lost.number.numberValue(BigDecimal::class.java).setScale(5, RoundingMode.HALF_UP), paid.currency)
    }
}

/** Identifies the usage of an offer or a discount, e.g. in the links to the licenses and customers with it. */
sealed interface OfferUsageKey {
    /** The parameter of the links to the licenses and customers with the offer or discount. */
    val queryParameter: Pair<String, String>

    /** An offer of the plugin. */
    data class Offer(val id: PluginOfferId) : OfferUsageKey {
        override val queryParameter get() = OFFER_PARAMETER to id.toString()
    }

    /** An offer, which isn't an offer of the plugin, by its name, see [OfferUsages.otherOffers]. */
    data class OtherOffer(val name: String) : OfferUsageKey {
        override val queryParameter get() = OTHER_OFFER_PARAMETER to name
    }

    /** A discount without an offer, by its description, see [OfferUsages.otherDiscounts] and [OfferUsages.continuityDiscounts]. */
    data class OtherDiscount(val description: String) : OfferUsageKey {
        override val queryParameter get() = DISCOUNT_PARAMETER to description
    }

    companion object {
        const val OFFER_PARAMETER = "offer"
        const val OTHER_OFFER_PARAMETER = "otherOffer"
        const val DISCOUNT_PARAMETER = "discount"

        /** @return The key of the parameters of a link, `null` without a valid parameter. */
        fun fromParameters(parameter: (String) -> String?): OfferUsageKey? {
            parameter(OFFER_PARAMETER)?.toIntOrNull()?.let { return Offer(it) }
            parameter(OTHER_OFFER_PARAMETER)?.takeIf(String::isNotBlank)?.let { return OtherOffer(it) }
            parameter(DISCOUNT_PARAMETER)?.takeIf(String::isNotBlank)?.let { return OtherDiscount(it) }
            return null
        }
    }
}

/**
 * How licenses used an offer or a discount.
 */
class OfferUsage(val key: OfferUsageKey, exchangeRates: ExchangeRates) {
    /** The purchases of licenses with the discount, a license renewed with the discount is counted for each renewal. */
    var licenses: Int = 0
        private set

    /** The purchases of licenses with a discount of 100 %. */
    var freeLicenses: Int = 0
        private set

    /** The purchases of licenses, whose lost amount is unknown, see [OfferDiscounts.lostAmount]. */
    var licensesWithoutLostAmount: Int = 0
        private set

    private val saleRefs = ObjectOpenHashSet<String>()
    private val customerIds = IntOpenHashSet()

    /** The purchases of licenses with the discount, compared by identity. */
    private val purchases: MutableSet<LicenseInfo> = Collections.newSetFromMap(IdentityHashMap())

    /** @return If the purchase of a license used the discount. */
    operator fun contains(license: LicenseInfo): Boolean = license in purchases

    /** @return If a purchase of the customer used the discount. */
    fun hasCustomer(customer: CustomerId): Boolean = customer in customerIds

    val sales: Int get() = saleRefs.size
    val customers: Int get() = customerIds.size

    /** The paid amounts, after all discounts. */
    val paid = MonetaryAmountTracker(exchangeRates)

    /** The amounts lost to the discount. */
    val lost = MonetaryAmountTracker(exchangeRates)

    /** The percents of the discounts of the licenses. */
    val percents: SortedSet<Double> = TreeSet()

    var firstUse: YearMonthDay? = null
        private set
    var lastUse: YearMonthDay? = null
        private set

    internal fun add(license: LicenseInfo, percent: Double?) {
        val date = license.sale.date
        licenses++
        purchases += license
        if (percent == 100.0 || license.saleLineItem.isFreeLicense) {
            freeLicenses++
        }
        saleRefs += license.sale.ref
        customerIds += license.sale.customer.code
        paid.add(date, license.amountUSD, license.amount)

        val lostUSD = OfferDiscounts.lostAmount(license.amountUSD, percent)
        val lostAmount = OfferDiscounts.lostAmount(license.amount, percent)
        if (lostUSD != null && lostAmount != null) {
            lost.add(date, lostUSD, lostAmount)
        } else {
            licensesWithoutLostAmount++
        }

        if (percent != null) {
            percents += percent
        }
        if (firstUse == null || date < firstUse!!) {
            firstUse = date
        }
        if (lastUse == null || date > lastUse!!) {
            lastUse = date
        }
    }
}

/**
 * The usage of the offers of a plugin by its licenses.
 *
 * A discount of a sale belongs to an offer, if it's described as the discount of an offer with the same name (see
 * [OfferDiscounts.offerName]). It's a heuristic: the sales don't contain the ID of the offer, a renamed offer doesn't
 * match its earlier sales. If several offers have the same name, the offer whose dates and license periods match the
 * sale is preferred.
 * Discounts of offers, which aren't in the list of offers, e.g. offers of JetBrains or deleted offers, are
 * [otherOffers], continuity discounts are [continuityDiscounts], special discounts of the Marketplace, e.g. for
 * students or volume discounts, are [marketplaceSpecials], the other discounts without an offer are [otherDiscounts].
 * Reseller discounts are ignored: they're internal discounts of the Marketplace, which don't reduce the amount paid
 * to the developer.
 */
class OfferUsages(offers: List<PluginOffer>, private val exchangeRates: ExchangeRates) {
    private val offersByName: Map<String, List<PluginOffer>> = offers.groupBy { it.name.trim() }

    private val offerUsages = HashMap<PluginOfferId, OfferUsage>()

    /** The usage of offers, which aren't in the list of offers, by the name of the offer. */
    val otherOffers = TreeMap<String, OfferUsage>(String.CASE_INSENSITIVE_ORDER)

    /** The usage of discounts without an offer, by their description. */
    val otherDiscounts = TreeMap<String, OfferUsage>(String.CASE_INSENSITIVE_ORDER)

    /** The usage of the continuity discounts, which are no offers, by their description, e.g. `20% continuity discount`. */
    val continuityDiscounts = TreeMap<String, OfferUsage>(String.CASE_INSENSITIVE_ORDER)

    /**
     * The usage of the special discounts of the Marketplace, e.g. for students, by the name of the offer or the
     * description of the discount, see [OfferDiscounts.isMarketplaceSpecial]. Offers of the plugin aren't specials.
     */
    val marketplaceSpecials = TreeMap<String, OfferUsage>(String.CASE_INSENSITIVE_ORDER)

    fun add(license: LicenseInfo) {
        for (discount in license.saleLineItem.discountDescriptions) {
            if (discount.isResellerDiscount) {
                continue
            }
            val offerName = OfferDiscounts.offerName(discount)
            when (offerName) {
                null -> when {
                    OfferDiscounts.isMarketplaceSpecial(discount.description) -> marketplaceSpecials
                    discount.isContinuityDiscount -> continuityDiscounts
                    else -> otherDiscounts
                }
                    .getOrPut(discount.description.trim()) { OfferUsage(OfferUsageKey.OtherDiscount(discount.description.trim()), exchangeRates) }
                    .add(license, discount.percent)

                else -> {
                    val offer = findOffer(offerName, license)
                    when (offer) {
                        null -> (if (OfferDiscounts.isMarketplaceSpecial(offerName)) marketplaceSpecials else otherOffers)
                            .getOrPut(offerName) { OfferUsage(OfferUsageKey.OtherOffer(offerName), exchangeRates) }
                            .add(license, discount.percent)
                        else -> offerUsages.getOrPut(offer.id) { OfferUsage(OfferUsageKey.Offer(offer.id), exchangeRates) }
                            // the percent of each sale: offers with the same name may have had other percents
                            .add(license, discount.percent)
                    }
                }
            }
        }
    }

    /** @return The usage of an offer, `null` if no license used it. */
    fun usage(offer: PluginOfferId): OfferUsage? = offerUsages[offer]

    /** @return The usage of an offer or a discount, `null` if no license used it. */
    fun usage(key: OfferUsageKey): OfferUsage? = when (key) {
        is OfferUsageKey.Offer -> offerUsages[key.id]
        is OfferUsageKey.OtherOffer -> otherOffers[key.name] ?: marketplaceSpecials[key.name]
        is OfferUsageKey.OtherDiscount -> otherDiscounts[key.description] ?: continuityDiscounts[key.description] ?: marketplaceSpecials[key.description]
    }

    private fun findOffer(name: String, license: LicenseInfo): PluginOffer? {
        val candidates = offersByName[name.trim()] ?: return null
        if (candidates.size == 1) {
            return candidates.first()
        }

        val date = license.sale.date
        val period = license.sale.licensePeriod
        fun PluginOffer.matchesPeriod() = licensePeriods.isEmpty() || period in licensePeriods
        fun PluginOffer.matchesDate() = startDate <= date && (endDate == null || date <= endDate!!)

        return candidates.firstOrNull { it.matchesDate() && it.matchesPeriod() }
            ?: candidates.filter { it.startDate <= date && it.matchesPeriod() }.maxByOrNull { it.startDate }
            ?: candidates.filter { it.startDate <= date }.maxByOrNull { it.startDate }
            ?: candidates.minBy { it.startDate }
    }
}
