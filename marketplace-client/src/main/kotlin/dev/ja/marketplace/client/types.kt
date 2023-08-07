/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal

typealias PluginId = Int
typealias PluginProductCode = String
typealias PluginUrl = String

typealias CustomerId = Int
typealias Country = String
typealias ResellerId = Int
typealias LicenseId = String

typealias Amount = BigDecimal

data class AmountWithCurrency(val amount: Amount, val currency: Currency)

fun Amount.withCurrency(currency: Currency): AmountWithCurrency {
    return AmountWithCurrency(this, currency)
}

interface WithAmounts {
    val amount: Amount
    val currency: Currency
    val amountUSD: Amount
}

@Serializable
data class PluginInfo(
    @SerialName("id")
    val id: PluginId,
    @SerialName("name")
    val name: String,
    @SerialName("link")
    val link: String,
    @SerialName("approve")
    val approve: Boolean,
    @SerialName("xmlId")
    val xmlID: String,
    @SerialName("description")
    val description: String,
    @SerialName("customIdeList")
    val customIdeList: Boolean,
    @SerialName("preview")
    val previewText: String,
    @SerialName("docText")
    val docText: String? = null,
    @SerialName("email")
    val email: String,
    @SerialName("cdate")
    val cdate: Long, // fixme verify
    @SerialName("family")
    val family: String,
    @SerialName("copyright")
    val copyright: String? = null,
    @SerialName("downloads")
    val downloads: Int,
    @SerialName("purchaseInfo")
    val purchaseInfo: PluginPurchaseInfo? = null,
    @SerialName("vendor")
    val vendor: PluginVendor? = null,
    @SerialName("urls")
    val urls: PluginUrls? = null,
    @SerialName("tags")
    val tags: List<PluginTag> = emptyList(),
    @SerialName("hasUnapprovedUpdate")
    val hasUnapprovedUpdate: Boolean,
    @SerialName("pricingModel")
    val pricingModel: PluginPricingModel,
    @SerialName("screens")
    val screens: List<PluginResourceUrl> = emptyList(),
    @SerialName("icon")
    val icon: String,
    @SerialName("isHidden")
    val isHidden: Boolean,
    @SerialName("isMonetizationAvailable")
    val isMonetizationAvailable: Boolean,
)

@Serializable
data class PluginPurchaseInfo(
    @SerialName("productCode")
    val productCode: PluginProductCode,
    @SerialName("buyUrl")
    val buyUrl: String? = null,
    @SerialName("purchaseTerms")
    val purchaseTerms: String? = null,
    @SerialName("optional")
    val optional: Boolean,
)

@Serializable
data class PluginVendor(
    @SerialName("name")
    val name: String,
    @SerialName("url")
    val url: PluginUrl,
    @SerialName("totalPlugins")
    val totalPlugins: Int,
    @SerialName("totalUsers")
    val totalUsers: Int,
    @SerialName("link")
    val link: String,
    @SerialName("publicName")
    val publicName: String,
    @SerialName("email")
    val email: String,
    @SerialName("countryCode")
    val countryCode: String,
    @SerialName("country")
    val country: Country,
    @SerialName("isVerified")
    val isVerified: Boolean,
    @SerialName("vendorId")
    val vendorId: Int,
    @SerialName("isTrader")
    val isTrader: Boolean,
    @SerialName("type")
    val type: String,
    @SerialName("servicesDescription")
    val servicesDescription: List<String>? = null,
)

@Serializable
data class PluginUrls(
    @SerialName("url")
    val url: PluginUrl,
    @SerialName("forumUrl")
    val forumUrl: PluginUrl? = null,
    @SerialName("licenseUrl")
    val licenseUrl: PluginUrl? = null,
    @SerialName("privacyPolicyUrl")
    val privacyPolicyUrl: PluginUrl? = null,
    @SerialName("bugtrackerUrl")
    val bugtrackerUrl: PluginUrl? = null,
    @SerialName("docUrl")
    val docUrl: PluginUrl? = null,
    @SerialName("sourceCodeUrl")
    val sourceCodeUrl: PluginUrl? = null,
    @SerialName("videoUrl")
    val videoUrl: PluginUrl? = null,
    @SerialName("customContacts")
    val customContacts: List<PluginCustomContact> = emptyList(),
)

@Serializable
data class PluginCustomContact(
    @SerialName("title")
    val title: String,
    @SerialName("link")
    val link: PluginUrl,
)

@Serializable
data class PluginTag(
    @SerialName("id")
    val id: Int,
    @SerialName("name")
    val name: String,
    @SerialName("privileged")
    val privileged: Boolean,
    @SerialName("searchable")
    val searchable: Boolean,
    @SerialName("link")
    val link: String,
)

@Serializable
enum class PluginPricingModel {
    @SerialName("PAID")
    Paid,

    //fixme verify
    @SerialName("FREEMIUM")
    Freemium,

    //fixme verify
    @SerialName("FREE")
    Free,
}

@Serializable
data class PluginResourceUrl(
    @SerialName("url")
    val url: PluginUrl,
)

@Serializable
data class PluginRating(
    @SerialName("userRating")
    val userRating: Int,
    @SerialName("meanVotes")
    val meanVotes: Int,
    @SerialName("meanRating")
    val meanRating: Double,
    @SerialName("votes")
    val votes: Map<Int, Int>,
) {
    val calculatedRatingValue: Double
        get() {
            return (weightedVotesSum + 2.0 * meanRating) / (votesSum + 2.0)
        }

    private val weightedVotesSum: Int
        get() {
            return votes.entries.sumOf { (weight, value) -> weight * value }
        }

    private val votesSum: Int
        get() {
            return votes.values.sum()
        }
}

@Serializable
data class PluginSale(
    @SerialName("ref")
    val ref: String,
    @SerialName("date")
    val date: YearMonthDay,
    @SerialName("amount")
    @Serializable(with = AmountSerializer::class)
    override val amount: Amount,
    @SerialName("amountUSD")
    @Serializable(with = AmountSerializer::class)
    override val amountUSD: Amount,
    @SerialName("currency")
    override val currency: Currency,
    @SerialName("period")
    val licensePeriod: LicensePeriod,
    @SerialName("customer")
    val customer: CustomerInfo,
    @SerialName("reseller")
    val reseller: ResellerInfo? = null,
    @SerialName("lineItems")
    val lineItems: List<PluginSaleItem>
) : Comparable<PluginSale>, WithAmounts {

    override fun compareTo(other: PluginSale): Int {
        return date.compareTo(other.date)
    }
}

@Serializable
enum class Currency {
    @SerialName("USD")
    USD,

    @SerialName("EUR")
    EUR,

    @SerialName("JPY")
    JPY,

    @SerialName("GBP")
    GBP,

    @SerialName("CZK")
    CZK,
}

@Serializable
enum class LicensePeriod {
    @SerialName("Monthly")
    Monthly,

    @SerialName("Annual")
    Annual,
}

@Serializable
data class CustomerInfo(
    val code: CustomerId,
    val name: String,
    val country: Country,
    val type: CustomerType,
) : Comparable<CustomerInfo> {
    override fun compareTo(other: CustomerInfo): Int {
        return code.compareTo(other.code)
    }
}

@Serializable
enum class CustomerType {
    @SerialName("Personal")
    Personal,

    @SerialName("Organization")
    Organization,
}

@Serializable
data class ResellerInfo(
    @SerialName("code")
    val code: ResellerId,
    @SerialName("name")
    val name: String,
    @SerialName("country")
    val country: Country,
    @SerialName("type")
    val type: ResellerType,
)

@Serializable
enum class ResellerType {
    @SerialName("Reseller")
    Reseller,
}

@Serializable
data class PluginSaleItem(
    @SerialName("type")
    val type: PluginSaleItemType,
    @SerialName("licenseIds")
    val licenseIds: List<LicenseId>,
    @SerialName("subscriptionDates")
    val subscriptionDates: YearMonthDayRange,
    @SerialName("amount")
    @Serializable(with = AmountSerializer::class)
    val amount: Amount,
    @SerialName("amountUsd")
    @Serializable(with = AmountSerializer::class)
    val amountUSD: Amount,
    @SerialName("discountDescriptions")
    val discountDescriptions: List<PluginSaleItemDiscount>
) {
    val isFreeLicense: Boolean = discountDescriptions.any { it.percent == 100.0 }
}

@Serializable
enum class PluginSaleItemType {
    @SerialName("NEW")
    New,

    @SerialName("RENEW")
    Renew,
}

@Serializable
data class PluginSaleItemDiscount(
    @SerialName("description")
    val description: String,
    @SerialName("percent")
    val percent: Double? = null,
)

@Serializable
data class PluginTrial(
    @SerialName("ref")
    val referenceId: String,
    @SerialName("date")
    val date: YearMonthDay,
    @SerialName("customer")
    val customer: CustomerInfo,
) : Comparable<PluginTrial> {

    override fun compareTo(other: PluginTrial): Int {
        return date.compareTo(other.date)
    }
}

