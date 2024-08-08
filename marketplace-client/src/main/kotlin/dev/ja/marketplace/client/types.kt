/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.services.Country
import io.ktor.http.*
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import javax.money.CurrencyUnit
import javax.money.Monetary
import javax.money.MonetaryAmount

typealias UserId = String
typealias PluginId = Int
typealias PluginXmlId = String
typealias PluginProductCode = String
typealias PluginUrl = String
typealias PluginChannel = String
typealias PluginReleaseId = Int

typealias CustomerId = Int
typealias ResellerId = Int
typealias LicenseId = String

typealias JetBrainsProductId = String
typealias PluginModuleName = String

typealias TrialId = String

object PluginChannelNames {
    const val Stable = ""
}

/**
 * API providing data about paid plugins.
 * This requires an API key.
 */
@Target(AnnotationTarget.FUNCTION)
annotation class PaidPluginAPI

interface WithAmounts {
    val amount: MonetaryAmount
    val amountUSD: MonetaryAmount
}

@Serializable
enum class MarketplaceProgram(@Transient val jsonId: String) {
    @SerialName("STUDENT")
    Student("STUDENT"),

    @SerialName("GRADUATION")
    FormerStudents("GRADUATION"),

    @SerialName("CLASSROOM")
    ClassroomAssistance("CLASSROOM"),

    @SerialName("DEVELOPER_RECOGNITION")
    DeveloperRecognition("DEVELOPER_RECOGNITION"),

    @SerialName("NON_PROFIT")
    NonProfit("NON_PROFIT"),

    @SerialName("OPEN_SOURCE")
    OpenSource("OPEN_SOURCE"),

    @SerialName("EDUCATIONAL_ORGANIZATION")
    EduOrganizations("EDUCATIONAL_ORGANIZATION"),

    @SerialName("START_UP")
    Startups("START_UP"),

    @SerialName("TRAINING")
    Bootcamps("TRAINING"),

    @SerialName("USER_GROUP")
    UserGroups("USER_GROUP")
}

@Serializable
enum class ProductFamily(@Transient val jsonId: String) {
    @SerialName("intellij")
    IntelliJ("intellij"),

    @SerialName("teamcity")
    TeamCity("teamcity"),

    @SerialName("hub")
    Hub("hub"),

    @SerialName("fleet")
    Fleet("fleet"),

    @SerialName("dotnet")
    DotNet("dotnet"),

    @SerialName("space")
    Space("space"),

    @SerialName("toolbox")
    ToolBox("toolbox"),

    @SerialName("edu")
    Edu("edu"),
}

@Serializable
enum class LicensingType {
    @SerialName("SUBSCRIPTION")
    Subscription,

    @SerialName("SUBSCRIPTION_FALLBACK")
    SubscriptionWithFallback,
}

@Serializable
data class PluginMajorVersion(
    @SerialName("version")
    val version: String,
    @SerialName("date")
    val date: YearMonthDay,
)

@Serializable
data class UserInfo(
    @SerialName("id")
    val id: UserId,
    @SerialName("name")
    val name: String,
)

/**
 * Plugin data properties shared by the different types of plugin
 * data JSON structures.
 */
interface PluginInfoBase {
    val id: PluginId
    val name: String
    val link: String?
}

interface PluginInfoExtended : PluginInfoBase {
    val xmlId: PluginXmlId
    val downloads: Int
    val tagNames: List<PluginTagName>
    val pricingModel: PluginPricingModel
    val previewText: String?
    val iconUrlPath: String?

    val isPaidOrFreemium: Boolean
        get() {
            return pricingModel == PluginPricingModel.Paid || pricingModel == PluginPricingModel.Freemium
        }
}

@Serializable
data class PluginInfoShort(
    /**
     * Unique ID of the plugin.
     */
    @SerialName("id")
    override val id: PluginId,
    /**
     * Display name of the plugin.
     */
    @SerialName("name")
    override val name: String,
    /**
     * Absolute URL path to the plugin page on the Marketplace.
     */
    @SerialName("link")
    override val link: String? = null,
) : PluginInfoBase

@Serializable
data class PluginInfoSummary(
    @SerialName("id")
    override val id: PluginId,
    @SerialName("xmlId")
    override val xmlId: PluginXmlId,
    @SerialName("name")
    override val name: String,
    @SerialName("link")
    override val link: String,
    @SerialName("preview")
    override val previewText: String,
    @SerialName("downloads")
    override val downloads: Int,
    @SerialName("pricingModel")
    override val pricingModel: PluginPricingModel,
    @SerialName("rating")
    val rating: Double,
    @SerialName("hasSource")
    val hasSource: Boolean,
    @SerialName("tags")
    override val tagNames: List<PluginTagName> = emptyList(),
    @SerialName("icon")
    override val iconUrlPath: String? = null,
    @SerialName("cdate")
    @Serializable(CDateSerializer::class)
    val createdTimestamp: Instant? = null,
    @SerialName("vendor")
    val vendor: PluginVendorShort? = null,
    @SerialName("organization")
    val organizationName: String? = null,
    @SerialName("target")
    val target: String? = null,
) : PluginInfoExtended {
}

@Serializable
data class PluginInfo(
    @SerialName("id")
    override val id: PluginId,
    @SerialName("name")
    override val name: String,
    @SerialName("link")
    override val link: String,
    @SerialName("approve")
    val approve: Boolean,
    @SerialName("xmlId")
    override val xmlId: String,
    @SerialName("description")
    val description: String,
    @SerialName("customIdeList")
    val customIdeList: Boolean,
    @SerialName("preview")
    override val previewText: String? = null,
    @SerialName("docText")
    val docText: String? = null,
    @SerialName("email")
    val email: String? = null,
    @SerialName("cdate")
    @Serializable(CDateSerializer::class)
    val createdTimestamp: Instant? = null,
    @SerialName("family")
    val family: ProductFamily,
    @SerialName("copyright")
    val copyright: String? = null,
    @SerialName("downloads")
    override val downloads: Int,
    @SerialName("purchaseInfo")
    val purchaseInfo: PluginPurchaseInfo? = null,
    @SerialName("vendor")
    val vendor: PluginVendor? = null,
    @SerialName("pluginXmlVendor")
    val pluginXmlVendor: String? = null,
    @SerialName("urls")
    val urls: PluginUrls? = null,
    @SerialName("tags")
    val tags: List<PluginTag> = emptyList(),
    @SerialName("hasUnapprovedUpdate")
    val hasUnapprovedUpdate: Boolean,
    @SerialName("pricingModel")
    override val pricingModel: PluginPricingModel,
    @SerialName("screens")
    val screens: List<PluginResourceUrl> = emptyList(),
    @SerialName("icon")
    override val iconUrlPath: String? = null,
    @SerialName("isHidden")
    val isHidden: Boolean,
    @SerialName("isMonetizationAvailable")
    val isMonetizationAvailable: Boolean? = null,
) : PluginInfoExtended {
    override val tagNames: List<PluginTagName>
        get() = tags.map(PluginTag::name)

    fun getLinkUrl(frontendUrl: Url = Marketplace.MarketplaceFrontendUrl): Url {
        return URLBuilder(frontendUrl).also {
            it.encodedPath = this.link
        }.build()
    }

    fun getIconUrl(frontendUrl: Url = Marketplace.MarketplaceFrontendUrl): Url? {
        this.iconUrlPath ?: return null
        return URLBuilder(frontendUrl).also {
            it.encodedPath = this.iconUrlPath
        }.build()
    }
}

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
    @SerialName("trialPeriod")
    val trialPeriod: Int? = null,
)

interface PluginVendorInformation {
    val name: String?
    val isVerified: Boolean?
}

@Serializable
data class PluginVendorShort(
    @SerialName("name")
    override val name: String,
    @SerialName("isVerified")
    override val isVerified: Boolean? = null,
) : PluginVendorInformation

@Serializable
data class PluginVendor(
    @SerialName("type")
    val type: String,
    @SerialName("name")
    override val name: String? = null,
    @SerialName("url")
    val url: PluginUrl? = null,
    @SerialName("totalPlugins")
    val totalPlugins: Int? = null,
    @SerialName("totalUsers")
    val totalUsers: Int? = null,
    @SerialName("link")
    val link: String? = null,
    @SerialName("publicName")
    val publicName: String? = null,
    @SerialName("email")
    val email: String? = null,
    @SerialName("countryCode")
    val countryCode: String? = null,
    @SerialName("country")
    val country: String? = null,
    @SerialName("isVerified")
    override val isVerified: Boolean? = null,
    @SerialName("vendorId")
    val vendorId: Int? = null,
    @SerialName("isTrader")
    val isTrader: Boolean? = null,
    @SerialName("servicesDescription")
    val servicesDescription: List<String>? = null,
    @SerialName("id")
    val id: Int? = null,
) : PluginVendorInformation

@Serializable
data class PluginUrls(
    @SerialName("url")
    val url: PluginUrl? = null,
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
enum class PluginPricingModel(val searchQueryValue: String) {
    @SerialName("PAID")
    Paid("PAID"),

    @SerialName("FREEMIUM")
    Freemium("FREEMIUM"),

    @SerialName("FREE")
    Free("FREE"),
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
            return (weightedVotesSum + 2.0 * meanRating) / (votesCount + 2.0)
        }

    val votesCount: Int
        get() {
            return votes.values.sum()
        }

    private val weightedVotesSum: Int
        get() {
            return votes.entries.sumOf { (weight, value) -> weight * value }
        }
}

@Serializable(PluginSaleSerializer::class)
data class PluginSale(
    val ref: String,
    val date: YearMonthDay,
    override val amount: MonetaryAmount,
    override val amountUSD: MonetaryAmount,
    val licensePeriod: LicensePeriod,
    val customer: CustomerInfo,
    val reseller: ResellerInfo? = null,
    val lineItems: List<PluginSaleItem>
) : Comparable<PluginSale>, WithAmounts {
    override fun compareTo(other: PluginSale): Int {
        return date.compareTo(other.date)
    }
}

object MarketplaceCurrencies : Iterable<CurrencyUnit> {
    val USD = Monetary.getCurrency("USD")
    private val EUR = Monetary.getCurrency("EUR")
    private val JPY = Monetary.getCurrency("JPY")
    private val GBP = Monetary.getCurrency("GBP")
    private val CZK = Monetary.getCurrency("CZK")
    private val CNY = Monetary.getCurrency("CNY")

    private val allCurrencies = listOf(USD, EUR, JPY, GBP, CZK, CNY)

    override fun iterator(): Iterator<CurrencyUnit> {
        return allCurrencies.iterator()
    }

    fun of(id: String): CurrencyUnit {
        return when (id) {
            "USD" -> USD
            "EUR" -> EUR
            "JPY" -> JPY
            "GBP" -> GBP
            "CZK" -> CZK
            "CNY" -> CNY
            else -> throw IllegalStateException("Unknown currency $id")
        }
    }
}

// the order of the enum values is used for sorting
@Serializable
enum class LicensePeriod(val linkSegmentName: String) {
    @SerialName("Annual")
    Annual("annual"),

    @SerialName("Monthly")
    Monthly("monthly"),
}

@Serializable
data class CustomerInfo(
    @SerialName("code")
    val code: CustomerId,
    @SerialName("country")
    val country: String,
    @SerialName("type")
    val type: CustomerType,
    @SerialName("name")
    @Serializable(with = NullableStringSerializer::class)
    val name: String? = null,
) : Comparable<CustomerInfo> {
    override fun compareTo(other: CustomerInfo): Int {
        return code.compareTo(other.code)
    }
}

// keep order because it's used for sorting
@Serializable
enum class CustomerType {
    @SerialName("Organization")
    Organization,

    @SerialName("Personal")
    Individual,
}

@Serializable
data class ResellerInfo(
    @SerialName("code")
    val code: ResellerId,
    @SerialName("name")
    val name: String,
    @SerialName("country")
    val country: String,
    @SerialName("type")
    val type: ResellerType,
) {
    val tooltip: String
        get() {
            return "$name ($country, type: $type, ID: $code)"
        }
}

@Serializable
enum class ResellerType(val displayString: String) {
    @SerialName("Reseller")
    Reseller("Reseller"),

    @SerialName("Organization")
    Organization("Organization"),
}

@Serializable
internal data class JsonPluginSaleItem(
    @SerialName("type")
    val type: PluginSaleItemType,
    @SerialName("licenseIds")
    val licenseIds: List<LicenseId>,
    @SerialName("subscriptionDates")
    val subscriptionDates: YearMonthDayRange,
    @SerialName("amount")
    val amount: Double,
    @SerialName("amountUsd")
    val amountUSD: Double,
    @SerialName("discountDescriptions")
    val discountDescriptions: List<PluginSaleItemDiscount>
)

data class PluginSaleItem(
    val type: PluginSaleItemType,
    val licenseIds: List<LicenseId>,
    val subscriptionDates: YearMonthDayRange,
    val amount: MonetaryAmount,
    val amountUSD: MonetaryAmount,
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
) {
    val isContinuityDiscount: Boolean
        get() {
            return description.contains(" continuity discount")
        }
}

@Serializable
data class PluginTrial(
    @SerialName("ref")
    val referenceId: TrialId,
    @SerialName("date")
    val date: YearMonthDay,
    @SerialName("customer")
    val customer: CustomerInfo,
) : Comparable<PluginTrial> {
    override fun compareTo(other: PluginTrial): Int {
        return date.compareTo(other.date)
    }
}

@Serializable
data class VolumeDiscountResponse(
    @SerialName("isEnabled")
    val enabled: Boolean,
    @SerialName("levels")
    val volumeDiscounts: List<VolumeDiscountLevel>,
)

@Serializable
data class VolumeDiscountLevel(
    @SerialName("quantity")
    val quantity: Int,
    @SerialName("discountPercent")
    val discountPercent: Int,
)

@Serializable
data class MarketplacePluginInfo(
    @SerialName("code")
    val code: String,
    @SerialName("name")
    val name: String,
    @SerialName("periods")
    val licensePeriod: List<LicensePeriod>,
    @SerialName("individualPrice")
    @Serializable(with = MonetaryAmountUsdSerializer::class)
    val individualPrice: MonetaryAmount? = null,
    @SerialName("businessPrice")
    @Serializable(with = MonetaryAmountUsdSerializer::class)
    val businessPrice: MonetaryAmount? = null,
    @SerialName("licensing")
    val licensingType: LicensingType? = null,
    @SerialName("status")
    val status: String? = null,
    @SerialName("allowResellers")
    val allowResellers: Boolean? = null,
    @SerialName("link")
    val pluginPageLink: String? = null,
    @SerialName("trialPeriod")
    val trialPeriod: Int? = null,
    @SerialName("hasContinuityDiscount")
    val hasContinuityDiscount: Boolean? = null,
    // only available with fullInfo=true
    @SerialName("versions")
    val majorVersions: List<PluginMajorVersion>? = null,
)

enum class DownloadCountType(val requestPathSegment: String) {
    Downloads("downloads-count"),
    DownloadsUnique("downloads-unique"),
}

enum class DownloadDimensionRequest(val requestPathSegment: String) {
    // downloads grouped by product code, e.g. IC for IntelliJ IDEA Community
    ProductCode("product_code"),

    // downloads grouped by month
    Month("month"),

    // downloads grouped by day
    Day("day"),
}

@Serializable
enum class DownloadDimension {
    // total downloads of a plugin
    @SerialName("plugin")
    Plugin,

    // downloads grouped by product code, e.g. IC for IntelliJ IDEA Community
    @SerialName("product_code")
    ProductCode,

    // downloads grouped by month
    @SerialName("month")
    Month,

    // downloads grouped by day
    @SerialName("day")
    Day,
}

@Serializable
data class DownloadResponse(
    @SerialName("measure")
    val measure: String,
    @SerialName("filters")
    val filters: List<DownloadFilter>,
    @SerialName("dim1")
    val dimension: DownloadDimension,
    @SerialName("data")
    val data: DownloadResponseData
)

//@Serializable
//data class DownloadResponseFilter(
//    @SerialName("name")
//    val name: String,
//    @SerialName("value")
//    val value: String,
//)

@Serializable
data class DownloadResponseData(
    @SerialName("dimension")
    val dimension: DownloadDimension,
    @SerialName("serie")
    val serie: List<DownloadResponseItem>
)

@Serializable
data class DownloadResponseItem(
    @SerialName("name")
    val name: String,
    @SerialName("value")
    val value: Long,
    @SerialName("nameComment")
    val comment: String? = null,
)

@Serializable
enum class DownloadFilterType(val requestParameterName: String) {
    @SerialName("plugin")
    Plugin("plugin"),

    @SerialName("update")
    Update("update"),

    @SerialName("country")
    Country("country"),

    @SerialName("productCode")
    ProductCode("product-code"),

    @SerialName("versionMajor")
    MajorVersion("versionMajor"),
}

@Serializable
data class DownloadFilter(
    @SerialName("name")
    val type: DownloadFilterType,
    @SerialName("value")
    val value: String
) {
    companion object {
        fun update(updateId: Int): DownloadFilter {
            return DownloadFilter(DownloadFilterType.Update, updateId.toString())
        }

        fun country(country: Country): DownloadFilter {
            return DownloadFilter(DownloadFilterType.Country, country.printableName)
        }

        fun productCode(productCode: String): DownloadFilter {
            return DownloadFilter(DownloadFilterType.ProductCode, productCode)
        }

        fun majorVersion(majorVersion: String): DownloadFilter {
            return DownloadFilter(DownloadFilterType.MajorVersion, majorVersion)
        }
    }
}

data class MonthlyDownload(val firstOfMonth: YearMonthDay, val downloads: Long)

data class DailyDownload(val day: YearMonthDay, val downloads: Long)

data class ProductDownload(val productCode: String, val productName: String?, val downloads: Long)

@Serializable
enum class PluginSearchProductId(val parameterValue: String) {
    ANDROIDSTUDIO("androidstudio"),
    APPCODE("appcode"),
    AQUA("aqua"),
    CLION("clion"),
    DATASPELL("dataspell"),
    DBE("dbe"),
    GO("go"),
    IDEA("idea"),
    IDEA_CE("idea_ce"),
    MPS("mps"),
    PHPSTORM("phpstorm"),
    PYCHARM("pycharm"),
    PYCHARM_CE("pycharm_ce"),
    RIDER("rider"),
    RUBY("ruby"),
    RUST("rust"),
    WEBSTORM("webstorm"),
    WRITERSIDE("writerside"),
}

@Serializable
enum class PluginSearchOrderBy(val parameterValue: String?) {
    Relevance(null),
    Name("name"),
    Downloads("downloads"),
    Rating("rating"),
    PublishDate("publish date"),
    UpdateDate("update date"),
}

data class MarketplacePluginSearchRequest(
    /* `null` means all results */
    val maxResults: Int? = null,
    val offset: Int = 0,
    val queryFilter: String? = null,
    val orderBy: PluginSearchOrderBy? = PluginSearchOrderBy.Relevance,
    val products: List<PluginSearchProductId>? = null,
    val requiredTags: List<PluginTagName> = emptyList(),
    val excludeTags: List<PluginTagName> = listOf(MarketplaceTag.Theme),
    val pricingModels: List<PluginPricingModel>? = null,
    val shouldHaveSource: Boolean? = null,
    val isFeaturedSearch: Boolean? = null,
)

@Serializable
data class MarketplacePluginSearchResponse(
    @SerialName("total")
    val totalResult: Int,
    @SerialName("correctedQuery")
    val correctedQuery: String? = null,
    @SerialName("plugins")
    val searchResult: List<MarketplacePluginSearchResultItem>,
)

@Serializable
data class MarketplacePluginSearchResultItem(
    @SerialName("id")
    override val id: PluginId,
    @SerialName("name")
    override val name: String,
    @SerialName("link")
    override val link: String? = null,
    @SerialName("xmlId")
    override val xmlId: String,
    @SerialName("preview")
    override val previewText: String? = null,
    @SerialName("downloads")
    override val downloads: Int,
    @SerialName("pricingModel")
    override val pricingModel: PluginPricingModel,
    @SerialName("hasSource")
    val hasSource: Boolean,
    @SerialName("tags")
    override val tagNames: List<PluginTagName> = emptyList(),
    @SerialName("organization")
    val organizationName: String? = null,
    @SerialName("icon")
    override val iconUrlPath: String? = null,
    @SerialName("previewImage")
    val previewImageUrlPath: String? = null,
    @SerialName("cdate")
    @Serializable(CDateSerializer::class)
    val createdTimestamp: Instant? = null,
    @SerialName("vendorInfo")
    val vendorInfo: PluginVendorShort? = null,
) : PluginInfoExtended

@Serializable
data class PluginReviewComment(
    @SerialName("id")
    val id: Long,
    @SerialName("cdate")
    @Serializable(CDateSerializer::class)
    val createdTimestamp: Instant? = null,
    @SerialName("comment")
    val comment: String,
    @SerialName("plugin")
    val plugin: PluginInfoShort,
    @SerialName("rating")
    val rating: Short,
    @SerialName("repliesCount")
    val repliesCount: Int,
    @SerialName("vendor")
    val vendor: Boolean,
    @SerialName("markedAsSpam")
    val markedAsSpam: Boolean,
    @SerialName("author")
    val author: JetBrainsAccountInfo? = null,
    @SerialName("votes")
    val votes: PluginCommentVotes? = null,
    // this property is only available for replies to other plugin comments
    @SerialName("parentId")
    val parentId: Int? = null,
)

@Serializable
data class JetBrainsAccountInfo(
    @SerialName("id")
    val id: UserId,
    @SerialName("name")
    val name: String? = null,
    @SerialName("link")
    val link: String? = null,
    @SerialName("hubLogin")
    val hubLogin: String? = null,
    @SerialName("icon")
    val iconUrl: String? = null,
    @SerialName("showMarketoCheckbox")
    val showMarketoCheckbox: Boolean? = null,
    @SerialName("isJetBrains")
    val isJetBrains: Boolean = false,
    // used in release info data and for the developers request
    @SerialName("personalVendorId")
    val personalVendorId: Int? = null,
) {
    fun getLinkUrl(frontendUrl: Url = Marketplace.MarketplaceFrontendUrl): Url? {
        this.link ?: return null
        return URLBuilder(frontendUrl).also {
            it.encodedPath = this.link
        }.build()
    }
}

@Serializable
data class PluginCommentVotes(
    @SerialName("positive")
    val positive: Int,
    @SerialName("negative")
    val negative: Int,
)

@Serializable
data class PluginReleaseInfo(
    @SerialName("id")
    val id: PluginReleaseId,
    @SerialName("pluginId")
    val pluginId: PluginId,
    @SerialName("link")
    val updateInfoUrlPath: String,
    @SerialName("version")
    val version: String,
    @SerialName("approve")
    val approve: Boolean,
    @SerialName("listed")
    val listed: Boolean,
    @SerialName("hidden")
    val hidden: Boolean,
    @SerialName("recalculateCompatibilityAllowed")
    val recalculateCompatibilityAllowed: Boolean? = null,
    @SerialName("cdate")
    @Serializable(CDateSerializer::class)
    val createdTimestamp: Instant? = null,
    @SerialName("file")
    val fileUrlPath: String,
    @SerialName("size")
    val fileSizeBytes: Long,
    @SerialName("notes")
    val notes: String? = null,
    @SerialName("since")
    val since: String? = null,
    @SerialName("until")
    val until: String? = null,
    @SerialName("sinceUntil")
    val sinceUntil: String? = null,
    @SerialName("channel")
    val channel: PluginChannel,
    @SerialName("downloads")
    val downloads: Int,
    @SerialName("compatibleVersions")
    val compatibleVersions: Map<JetBrainsProductId, String>? = null,
    @SerialName("author")
    val author: JetBrainsAccountInfo? = null,
    @SerialName("modules")
    val modules: List<PluginModuleName>? = null,
    /** Release version of a paid or freemium plugin */
    @SerialName("releaseVersion")
    val releaseVersion: String? = null,
) {
    val isPaidOrFreemiumUpdate: Boolean
        get() {
            return releaseVersion != null
        }

    fun getUpdateInfoPageUrl(frontendUrl: Url = Marketplace.MarketplaceFrontendUrl): Url {
        return URLBuilder(frontendUrl).also {
            it.encodedPath = this.updateInfoUrlPath
        }.build()
    }

    fun getMarketplaceDownloadLink(frontendUrl: Url = Marketplace.MarketplaceFrontendUrl): Url {
        return URLBuilder(frontendUrl).also {
            it.encodedPath = "/plugin/download"
            it.parameters["updateId"] = id.toString()
            it.parameters["rel"] = "true"
        }.build()
    }
}

@Serializable
data class PluginPriceInfo(
    @SerialName("shopBuyUrl")
    val shopBuyUrl: String,
    @SerialName("shopQuoteUrl")
    val shopQuoteUrl: String,
    @SerialName("currency")
    val currency: CurrencyInfo,
    @SerialName("pluginInfo")
    val prices: PluginPriceInfoByType,
)

@Serializable
data class CurrencyInfo(
    @SerialName("iso")
    val currencyIsoId: String,
    @SerialName("symbol")
    val symbol: String,
    @SerialName("prefixSymbol")
    val prefixSymbol: Boolean,
)

@Serializable
data class PluginPriceInfoByType(
    @SerialName("personal")
    val personal: PriceInfoByPeriod,
    @SerialName("commercial")
    val commercial: PriceInfoByPeriod,
)

@Serializable
data class PriceInfoByPeriod(
    @SerialName("annual")
    val annual: PriceInfoTypeData,
    @SerialName("monthly")
    val monthly: PriceInfoTypeData? = null,
)

@Serializable
data class PriceInfoTypeData(
    @SerialName("firstYear")
    val firstYear: PriceInfoData,
    @SerialName("secondYear")
    val secondYear: PriceInfoData,
    @SerialName("thirdYear")
    val thirdYear: PriceInfoData,
)

@Serializable
data class PriceInfoData(
    @SerialName("price")
    @Serializable(BigDecimalSerializer::class)
    val price: BigDecimal,
    @SerialName("priceTaxed")
    @Serializable(BigDecimalSerializer::class)
    val priceTaxed: BigDecimal? = null,
    @SerialName("newShopCode")
    val newShopCode: String,
)

@Serializable
data class PluginDependency(
    @SerialName("name")
    val dependencyName: String,
    @SerialName("plugin")
    val plugin: PluginInfoShort? = null,
)

@Serializable
data class PluginUnsupportedProduct(
    @SerialName("name")
    val productName: String,
    @SerialName("dependencies")
    val dependencies: List<String>,
)
