/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import dev.ja.marketplace.client.Marketplace
import dev.ja.marketplace.client.PluginChannelNames
import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.YearMonthDayRange
import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.data.PercentageValue
import dev.ja.marketplace.data.subscriptions.LicenseCohorts
import dev.ja.marketplace.data.trackers.SimpleTrialTracker
import dev.ja.marketplace.data.trackers.maxTrialDays
import dev.ja.marketplace.data.trackers.trialConversionDays
import dev.ja.marketplace.data.transfers.LicenseTransfers
import dev.ja.marketplace.mcp.MarketplaceMcpTools.Companion.booleanProperty
import dev.ja.marketplace.mcp.MarketplaceMcpTools.Companion.dateProperty
import dev.ja.marketplace.mcp.MarketplaceMcpTools.Companion.endDateProperty
import dev.ja.marketplace.mcp.MarketplaceMcpTools.Companion.enumProperty
import dev.ja.marketplace.mcp.MarketplaceMcpTools.Companion.integerProperty
import dev.ja.marketplace.mcp.MarketplaceMcpTools.Companion.limitProperty
import dev.ja.marketplace.mcp.MarketplaceMcpTools.Companion.offsetProperty
import dev.ja.marketplace.mcp.MarketplaceMcpTools.Companion.pluginIdProperty
import dev.ja.marketplace.mcp.MarketplaceMcpTools.Companion.startDateProperty
import dev.ja.marketplace.mcp.MarketplaceMcpTools.Companion.stringProperty
import dev.ja.marketplace.services.JetBrainsProductCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Tools for the analysis of changes: single trials, downloads by product and plugin version, license cohorts,
 * reviews, visitors of the plugin page and transfers of licenses.
 */
class McpAnalysisTools(private val provider: McpDataProvider) {
    val tools: List<McpToolDefinition> = listOf(
        McpToolDefinition(
            "list_trials",
            "Trials",
            "Single trials with customer and conversion status, and counts per month. Status: converted (new paid license " +
                    "within conversionDays = trial length + ${Marketplace.TRIAL_CONVERSION_EXTRA_DAYS} days), convertedLater, pending " +
                    "(can still convert) or notConverted. conversionRate = converted/trials, conversionRateOfDecided excludes pending trials.",
            buildJsonObject {
                put("pluginId", pluginIdProperty)
                put("startDate", startDateProperty)
                put("endDate", endDateProperty)
                put("customerId", integerProperty("Only trials of this customer"))
                put("country", stringProperty("Country, name or ISO code. Empty string for trials without a country"))
                put("customerType", enumProperty(CustomerType.entries.map { it.toolName }))
                put("summaryOnly", booleanProperty("Only the counts, without the single trials"))
                put("limit", limitProperty)
                put("offset", offsetProperty)
            },
            listOf("pluginId"),
        ) { arguments -> listTrials(arguments) },
        McpToolDefinition(
            "get_downloads",
            "Downloads by product and plugin version",
            "Downloads per month or day by IDE product, plugin version or both (one request per product, slow). " +
                    "Unique downloads count each computer once per period. Groups without downloads are left out. " +
                    "The values of each group are in the order of dates. " +
                    "Values since incompleteFrom are incomplete (processed with a delay of a day). " +
                    "Downloads by IDE version and installs aren't available in the API.",
            buildJsonObject {
                put("pluginId", pluginIdProperty)
                put("groupBy", enumProperty(DownloadGrouping.entries.map { it.id }, "Default: product"))
                put("granularity", enumProperty(listOf("month", "day"), "Default: month"))
                put("unique", booleanProperty("false for all downloads. Default: true"))
                put("productCode", stringProperty("Only downloads by this product, e.g. IIU"))
                put("startDate", dateProperty("First day, inclusive. Default: 12 months ago"))
                put("endDate", endDateProperty)
            },
            listOf("pluginId"),
        ) { arguments -> getDownloads(arguments) },
        McpToolDefinition(
            "get_license_cohorts",
            "License cohorts",
            "Subscription licenses by the month of their first paid subscription (cohort) and license period. Per renewal n: " +
                    "due = licenses renewed n-1 times whose n-th renewal is decided, renewed = licenses renewed at least n times, " +
                    "pending = can still renew, renewalRate = renewed/due, retention = renewed/decided (decided = cohort licenses with known outcome). " +
                    "Renewed means the next subscription starts at most ${Marketplace.LICENSE_RENEWAL_GRACE_DAYS} days after the end. " +
                    "The range selects the cohort months.",
            buildJsonObject {
                put("pluginId", pluginIdProperty)
                put("licensePeriod", enumProperty(listOf(LicensePeriod.Annual.toolName, LicensePeriod.Monthly.toolName), "Default: both"))
                put("maxRenewals", integerProperty("Default: 5 for annual, 12 for monthly licenses"))
                put("startDate", startDateProperty)
                put("endDate", endDateProperty)
            },
            listOf("pluginId"),
        ) { arguments -> getLicenseCohorts(arguments) },
        McpToolDefinition(
            "list_reviews",
            "Reviews and ratings",
            "Reviews (without spam) with date, rating 1-5 (0 for a comment without rating), text, author, votes, sub-ratings, " +
                    "IDE and plugin version and resolve state, the average rating per month, and the current rating, " +
                    "which also counts ratings without a review. Resolved and deleted reviews and comments without rating " +
                    "don't count for the rating (countsForRating), the averages only use the reviews, which count.",
            buildJsonObject {
                put("pluginId", pluginIdProperty)
                put("startDate", startDateProperty)
                put("endDate", endDateProperty)
                put("includeText", booleanProperty("Default: true"))
            },
            listOf("pluginId"),
        ) { arguments -> listReviews(arguments) },
        McpToolDefinition(
            "get_page_views",
            "Visitors of the plugin page",
            "Page views, users and sessions of the plugin page per day, users by country and sessions by source. " +
                    "Sampled Google Analytics data: the Marketplace warns that visits and especially the sources may be inaccurate, " +
                    "days may be missing and allTime may lack recent data. Use it for trends only.",
            buildJsonObject {
                put("pluginId", pluginIdProperty)
                put("timeFrame", enumProperty(PageAnalyticsTimeFrame.entries.map { it.toolId }, "Default: lastYear"))
            },
            listOf("pluginId"),
        ) { arguments -> getPageViews(arguments) },
        McpToolDefinition(
            "list_license_transfers",
            "License transfers",
            "Licenses bought by another customer than the previous purchase, e.g. an individual moving to an employer, with counts " +
                    "by customer types. isFromCustomerActive=false means the previous customer holds no license anymore, although the license is used.",
            buildJsonObject {
                put("pluginId", pluginIdProperty)
                put("fromCustomerType", enumProperty(CustomerType.entries.map { it.toolName }))
                put("toCustomerType", enumProperty(CustomerType.entries.map { it.toolName }))
                put("startDate", startDateProperty)
                put("endDate", endDateProperty)
            },
            listOf("pluginId"),
        ) { arguments -> listLicenseTransfers(arguments) },
    )

    private enum class DownloadGrouping(val id: String) {
        Product("product"),
        PluginVersion("pluginVersion"),
        ProductAndPluginVersion("productAndPluginVersion"),
    }

    private val PageAnalyticsTimeFrame.toolId: String
        get() = requestParameter.replaceFirstChar(Char::lowercaseChar)

    private suspend fun listTrials(arguments: JsonObject): JsonObject {
        val plugin = provider.findPlugin(arguments)
        val dateRange = McpDateRange.parse(arguments.stringArgument("startDate"), arguments.stringArgument("endDate"))
        val range = dateRange.toRange()
        val customerId = arguments.intArgument("customerId")
        val customerType = arguments.enumArgument<CustomerType>("customerType")
        val summaryOnly = arguments.booleanArgument("summaryOnly") ?: false
        val page = Page.of(arguments)

        val data = provider.pluginData(plugin)
        val country = arguments.stringArgument("country")?.trim()?.let { value ->
            // ISO codes are mapped to the names, which are used by the customers
            data.countries.byCountryIsoCode(value.uppercase())?.country?.printableName ?: value
        }

        val allTrials = data.getTrials() ?: emptyList()
        val tracker = SimpleTrialTracker()
        tracker.init(allTrials)
        data.getSales()?.forEach(tracker::processSale)
        val sales = tracker.getResult(YearMonthDayRange.MAX).convertedTrials

        val today = YearMonthDay.now()
        val maxTrialDays = data.getPluginInfo().maxTrialDays
        val conversionDays = trialConversionDays(maxTrialDays)

        data class TrialStatus(val trial: PluginTrial, val status: String, val sale: PluginSale?)

        val trials = allTrials
            .filter { trial ->
                trial.date in range
                        && (customerId == null || trial.customer.code == customerId)
                        && (customerType == null || trial.customer.type == customerType)
                        && (country == null || trial.customer.country.equals(country, ignoreCase = true))
            }
            .sortedBy { it.date }
            .map { trial ->
                val sale = sales[trial]
                val status = when {
                    sale != null && trial.date.daysUntil(sale.date) <= conversionDays -> "converted"
                    sale != null -> "convertedLater"
                    trial.date.daysUntil(today) <= conversionDays -> "pending"
                    else -> "notConverted"
                }
                TrialStatus(trial, status, sale)
            }

        fun JsonObjectBuilder.putCounts(items: List<TrialStatus>) {
            val converted = items.count { it.status == "converted" }
            val pending = items.count { it.status == "pending" }
            put("trials", items.size)
            put("withoutCountry", items.count { it.trial.customer.country.isBlank() })
            put("organizations", items.count { it.trial.customer.type == CustomerType.Organization })
            put("converted", converted)
            put("convertedLater", items.count { it.status == "convertedLater" })
            put("pending", pending)
            put("conversionRate", DataTableJson.cellValue(PercentageValue.of(converted, items.size)))
            put("conversionRateOfDecided", DataTableJson.cellValue(PercentageValue.of(converted, items.size - pending)))
        }

        val json = McpRecordsJson(data.exchangeRates)
        return buildJsonObject {
            putPlugin(plugin)
            put("startDate", dateRange.start?.asIsoString)
            put("endDate", dateRange.end?.asIsoString)
            put("trialDays", maxTrialDays)
            put("conversionDays", conversionDays)
            put("summary", buildJsonObject { putCounts(trials) })
            put("byMonth", buildJsonArray {
                for ((month, items) in trials.groupBy { "%04d-%02d".format(it.trial.date.year, it.trial.date.month) }) {
                    add(buildJsonObject {
                        put("month", month)
                        putCounts(items)
                    })
                }
            })
            if (!summaryOnly) {
                page.putInfo(this, trials.size)
                put("trialList", buildJsonArray {
                    for ((trial, status, sale) in page.apply(trials)) {
                        add(buildJsonObject {
                            put("refNum", trial.referenceId)
                            put("date", trial.date.asIsoString)
                            put("customer", json.customer(trial.customer))
                            put("status", status)
                            if (sale != null) {
                                put("purchaseDate", sale.date.asIsoString)
                                put("purchaseRefNum", sale.ref)
                                put("daysToPurchase", trial.date.daysUntil(sale.date))
                            }
                        })
                    }
                })
            }
        }
    }

    private suspend fun getDownloads(arguments: JsonObject): JsonObject {
        val plugin = provider.findPlugin(arguments)
        val grouping = arguments.stringArgument("groupBy")?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
            DownloadGrouping.entries.firstOrNull { it.id.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("groupBy must be one of ${DownloadGrouping.entries.joinToString { it.id }}, but was '$value'")
        } ?: DownloadGrouping.Product
        val dimension = when (arguments.stringArgument("granularity")?.trim()?.lowercase()?.takeIf(String::isNotEmpty)) {
            null, "month" -> DownloadRequestDimension.Month
            "day" -> DownloadRequestDimension.Day
            else -> throw IllegalArgumentException("granularity must be month or day")
        }
        val countType = when (arguments.booleanArgument("unique")) {
            false -> DownloadCountType.Downloads
            else -> DownloadCountType.DownloadsUnique
        }
        val productCode = arguments.stringArgument("productCode")?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
            ?.let(JetBrainsProductCode::byProductCode)

        val parsedRange = McpDateRange.parse(arguments.stringArgument("startDate"), arguments.stringArgument("endDate"))
        val today = YearMonthDay.now()
        val dateRange = McpDateRange(parsedRange.start ?: YearMonthDay(today.year, today.month, 1).add(0, -11, 0), parsedRange.end)
        // the API returns the values of the months or days since this date, a monthly value contains the whole first month
        val requestStart = when (dimension) {
            DownloadRequestDimension.Month -> YearMonthDay(dateRange.start!!.year, dateRange.start.month, 1)
            else -> dateRange.start!!
        }
        val range = dateRange.toRange()

        data class Group(val productCode: String?, val productName: String?, val releaseId: String?, val series: List<DownloadResponseItem>)

        // a monthly value is named after the first day of the month, it's included if the month intersects the range
        fun isInRange(item: DownloadResponseItem): Boolean {
            val date = YearMonthDay.parse(item.name)
            return when (dimension) {
                DownloadRequestDimension.Month -> YearMonthDayRange.ofMonth(date.year, date.month).isIntersecting(range)
                else -> date in range
            }
        }

        fun DownloadResponseGroup.toGroup(product: String?, productName: String?, release: String?): Group {
            return Group(product, productName, release, serie.filter(::isInRange))
        }

        suspend fun request(groupType: DownloadRequestDimension, product: JetBrainsProductCode?): List<DownloadResponseGroup> {
            val filters = listOfNotNull(product?.let(DownloadFilter::productCode)).toTypedArray()
            return provider.groupedDownloads(plugin, dimension, groupType, countType, requestStart, *filters).groups
        }

        val groups = when (grouping) {
            DownloadGrouping.Product -> request(DownloadRequestDimension.ProductCode, productCode)
                .map { it.toGroup(it.name, it.comment, null) }

            DownloadGrouping.PluginVersion -> request(DownloadRequestDimension.Update, productCode)
                .map { it.toGroup(productCode?.code, null, it.name) }

            DownloadGrouping.ProductAndPluginVersion -> {
                val products = request(DownloadRequestDimension.ProductCode, productCode)
                    .map { it.toGroup(it.name, it.comment, null) }
                    .filter { group -> group.series.any { it.value > 0 } }
                products.flatMap { product ->
                    when (product.productCode) {
                        // downloads by an unknown product can't be requested by version
                        null, "unknown" -> listOf(product)
                        else -> request(DownloadRequestDimension.Update, JetBrainsProductCode.byProductCode(product.productCode))
                            .map { it.toGroup(product.productCode, product.productName, it.name) }
                    }
                }
            }
        }

        val versions = if (grouping == DownloadGrouping.Product) emptyMap() else releaseVersions(plugin)
        val sortedGroups = groups
            .map { it to it.series.sumOf(DownloadResponseItem::value) }
            .filter { (_, total) -> total > 0 }
            .sortedByDescending { it.second }

        return buildJsonObject {
            putPlugin(plugin)
            put("groupBy", grouping.id)
            put("granularity", dimension.requestPathSegment)
            put("unique", countType == DownloadCountType.DownloadsUnique)
            put("productCode", productCode?.code)
            put("startDate", dateRange.start?.asIsoString)
            put("endDate", dateRange.end?.asIsoString)
            // the values of the current period and the day before aren't complete yet
            val incompleteFrom = when (dimension) {
                DownloadRequestDimension.Month -> YearMonthDay(today.add(0, 0, -1).year, today.add(0, 0, -1).month, 1)
                else -> today.add(0, 0, -1)
            }
            if (incompleteFrom in range) {
                put("incompleteFrom", incompleteFrom.asIsoString)
            }
            put("totalDownloads", sortedGroups.sumOf { it.second })
            // the values of the groups are in the order of the dates, 0 if a group has no value for a date
            val dates = sortedGroups.flatMap { (group, _) -> group.series.map { it.name } }.distinct().sorted()
            put("dates", buildJsonArray { dates.forEach { add(it) } })
            put("groups", buildJsonArray {
                for ((group, total) in sortedGroups) {
                    add(buildJsonObject {
                        if (grouping != DownloadGrouping.PluginVersion || group.productCode != null) {
                            put("productCode", group.productCode)
                            put("productName", group.productName)
                        }
                        if (grouping != DownloadGrouping.Product) {
                            put("releaseId", group.releaseId)
                            put("pluginVersion", group.releaseId?.toIntOrNull()?.let(versions::get))
                        }
                        put("total", total)
                        val values = group.series.associate { it.name to it.value }
                        put("values", buildJsonArray { dates.forEach { add(values[it] ?: 0L) } })
                    })
                }
            })
        }
    }

    /**
     * @return The versions of the releases of all channels by their ID, empty if the releases are unavailable.
     *  Removed releases are not included.
     */
    private suspend fun releaseVersions(plugin: PluginInfoSummary): Map<Int, String> {
        return try {
            val channels = (provider.pluginChannels(plugin) + PluginChannelNames.Stable).distinct()
            channels.flatMap { provider.pluginReleases(plugin, it) }.associate { it.id to it.version }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun getLicenseCohorts(arguments: JsonObject): JsonObject {
        val plugin = provider.findPlugin(arguments)
        val dateRange = McpDateRange.parse(arguments.stringArgument("startDate"), arguments.stringArgument("endDate"))
        val range = dateRange.toRange()
        val licensePeriod = arguments.enumArgument<LicensePeriod>("licensePeriod")
        require(licensePeriod != LicensePeriod.Perpetual) { "Perpetual licenses have no renewals" }
        val maxRenewals = arguments.intArgument("maxRenewals")
        require(maxRenewals == null || maxRenewals in 1..120) { "maxRenewals must be between 1 and 120" }

        val licenses = provider.pluginData(plugin).getLicenses() ?: emptyList()
        // the limit of the renewals depends on the license period, both periods are calculated separately
        val cohorts = listOf(LicensePeriod.Annual to 5, LicensePeriod.Monthly to 12)
            .filter { (period, _) -> licensePeriod == null || period == licensePeriod }
            .flatMap { (period, defaultMax) ->
                LicenseCohorts.of(licenses, maxRenewals ?: defaultMax).filter { it.licensePeriod == period }
            }
            .filter { YearMonthDayRange.ofMonth(it.month.year, it.month.month).isIntersecting(range) }
            .sortedWith(compareBy({ it.licensePeriod }, { it.month }))

        return buildJsonObject {
            putPlugin(plugin)
            put("startDate", dateRange.start?.asIsoString)
            put("endDate", dateRange.end?.asIsoString)
            put("cohorts", buildJsonArray {
                for (cohort in cohorts) {
                    add(buildJsonObject {
                        put("month", "%04d-%02d".format(cohort.month.year, cohort.month.month))
                        put("licensePeriod", cohort.licensePeriod.toolName)
                        put("licenses", cohort.licenses)
                        put("renewals", buildJsonArray {
                            for (renewal in cohort.renewals) {
                                add(buildJsonObject {
                                    put("renewal", renewal.renewal)
                                    put("due", renewal.due)
                                    put("renewed", renewal.renewed)
                                    put("pending", renewal.pending)
                                    put("decided", renewal.decided)
                                    put("renewalRate", DataTableJson.cellValue(renewal.renewalRate))
                                    put("retention", DataTableJson.cellValue(renewal.retention))
                                    put("incomplete", renewal.isIncomplete)
                                })
                            }
                        })
                    })
                }
            })
        }
    }

    private suspend fun listReviews(arguments: JsonObject): JsonObject {
        val plugin = provider.findPlugin(arguments)
        val dateRange = McpDateRange.parse(arguments.stringArgument("startDate"), arguments.stringArgument("endDate"))
        val range = dateRange.toRange()
        val includeText = arguments.booleanArgument("includeText") ?: true

        val reviews = provider.reviews(plugin)
            .filter { !it.markedAsSpam && it.parentId == null }
            .mapNotNull { review -> review.createdTimestamp?.let { YearMonthDay.of(it) to review } }
            .filter { (date, _) -> date in range }
            .sortedBy { it.first }
        val rating = provider.pluginData(plugin).getPluginRating()

        return buildJsonObject {
            putPlugin(plugin)
            put("startDate", dateRange.start?.asIsoString)
            put("endDate", dateRange.end?.asIsoString)
            put("currentRating", buildJsonObject {
                put("rating", BigDecimal(rating.calculatedRatingValue).setScale(2, RoundingMode.HALF_UP))
                put("votes", rating.votesCount)
                put("votesByRating", buildJsonObject {
                    for ((stars, count) in rating.votes.entries.sortedBy { it.key }) {
                        put(stars.toString(), count)
                    }
                })
            })
            put("reviewCount", reviews.size)
            put("byMonth", buildJsonArray {
                for ((month, items) in reviews.groupBy { (date, _) -> "%04d-%02d".format(date.year, date.month) }) {
                    val counted = items.map { it.second }.filter(::countsForRating)
                    add(buildJsonObject {
                        put("month", month)
                        put("reviews", items.size)
                        put("countedReviews", counted.size)
                        put("averageRating", counted.takeIf { it.isNotEmpty() }?.let { reviews ->
                            BigDecimal(reviews.map { it.rating.toInt() }.average()).setScale(2, RoundingMode.HALF_UP)
                        })
                    })
                }
            })
            put("reviews", buildJsonArray {
                for ((date, review) in reviews) {
                    add(buildJsonObject {
                        put("id", review.id)
                        put("date", date.asIsoString)
                        put("rating", review.rating)
                        put("author", review.author?.name)
                        if (includeText) {
                            put("text", review.comment)
                        }
                        put("replies", review.repliesCount)
                        put("votesPositive", review.votes?.positive)
                        put("votesNegative", review.votes?.negative)
                        put("countsForRating", countsForRating(review))
                        review.resolveState?.let { put("resolveState", it) }
                        if (review.isResolveRequested) {
                            put("resolveRequested", true)
                        }
                        if (review.isDeleted) {
                            put("isDeleted", true)
                        }
                        review.subRating?.takeIf { it.isNotEmpty() }?.let { subRatings ->
                            put("subRatings", buildJsonObject { subRatings.toSortedMap().forEach { (aspect, value) -> put(aspect, value) } })
                        }
                        review.ideVersion?.let { put("ideVersion", it) }
                        review.releaseVersion?.let { put("pluginVersion", it) }
                    })
                }
            })
        }
    }

    /**
     * Resolved and deleted reviews and comments without rating don't count for the rating of the plugin.
     * Without them, the reviews match the votes of the rating, except ratings without a review.
     */
    private fun countsForRating(review: PluginReviewComment): Boolean {
        return review.rating in 1..5 && !review.isResolved && !review.isDeleted
    }

    private suspend fun getPageViews(arguments: JsonObject): JsonObject {
        val plugin = provider.findPlugin(arguments)
        val timeFrame = arguments.stringArgument("timeFrame")?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
            PageAnalyticsTimeFrame.entries.firstOrNull { it.toolId.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("timeFrame must be one of ${PageAnalyticsTimeFrame.entries.joinToString { it.toolId }}, but was '$value'")
        } ?: PageAnalyticsTimeFrame.LastYear

        val analytics = provider.pageAnalytics(plugin, timeFrame)
        val days = analytics.days.sortedBy { it.day }
        return buildJsonObject {
            putPlugin(plugin)
            put("timeFrame", timeFrame.toolId)
            put("isReliable", analytics.isReliable)
            put("note", "Sampled data of Google Analytics, page visits and especially the sources may be inaccurate.")
            put("firstDate", days.firstOrNull()?.let { analyticsDate(it.day) })
            put("lastDate", days.lastOrNull()?.let { analyticsDate(it.day) })
            put("totalPageViews", days.sumOf { it.pageViews })
            put("days", buildJsonArray {
                for (day in days) {
                    add(buildJsonObject {
                        put("date", analyticsDate(day.day))
                        put("pageViews", day.pageViews)
                        put("users", day.users)
                        put("sessions", day.sessions)
                    })
                }
            })
            put("countries", buildJsonArray {
                for (country in analytics.countries.sortedByDescending { it.users }) {
                    add(buildJsonObject {
                        put("country", country.country)
                        put("users", country.users)
                        put("sessions", country.sessions)
                    })
                }
            })
            put("sources", buildJsonArray {
                for (source in analytics.sources.sortedByDescending { it.sessions }) {
                    add(buildJsonObject {
                        put("source", source.source)
                        put("sessions", source.sessions)
                    })
                }
            })
        }
    }

    /**
     * @return The date in ISO format, or the value of the API if it's not in the expected format `yyyyMMdd`.
     */
    private fun analyticsDate(value: String): String {
        return try {
            LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).toString()
        } catch (_: Exception) {
            value
        }
    }

    private suspend fun listLicenseTransfers(arguments: JsonObject): JsonObject {
        val plugin = provider.findPlugin(arguments)
        val dateRange = McpDateRange.parse(arguments.stringArgument("startDate"), arguments.stringArgument("endDate"))
        val range = dateRange.toRange()
        val fromType = arguments.enumArgument<CustomerType>("fromCustomerType")
        val toType = arguments.enumArgument<CustomerType>("toCustomerType")

        val data = provider.pluginData(plugin)
        val transfers = LicenseTransfers.find(data.getLicenses() ?: emptyList())
            .filter { it.date in range && (fromType == null || it.from.type == fromType) && (toType == null || it.to.type == toType) }

        val json = McpRecordsJson(data.exchangeRates)
        return buildJsonObject {
            putPlugin(plugin)
            put("startDate", dateRange.start?.asIsoString)
            put("endDate", dateRange.end?.asIsoString)
            put("transferCount", transfers.size)
            put("fromInactiveCustomers", transfers.count { !it.isFromCustomerActive })
            put("byCustomerTypes", buildJsonObject {
                for ((types, items) in transfers.groupBy { "${it.from.type.toolName} -> ${it.to.type.toolName}" }.entries.sortedBy { it.key }) {
                    put(types, items.size)
                }
            })
            put("transfers", buildJsonArray {
                for (transfer in transfers) {
                    add(buildJsonObject {
                        put("licenseId", transfer.licenseId)
                        put("date", transfer.date.asIsoString)
                        put("from", json.customer(transfer.from))
                        put("to", json.customer(transfer.to))
                        put("previousValidUntil", transfer.previousValidUntil?.asIsoString)
                        put("isFromCustomerActive", transfer.isFromCustomerActive)
                    })
                }
            })
        }
    }
}
