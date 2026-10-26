/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.LenientDateSerializer
import dev.ja.marketplace.client.YearMonthDay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The result of the Check License API, see [dev.ja.marketplace.client.MarketplaceClient.checkLicense].
 */
@Serializable
data class LicenseCheckResult(
    @SerialName("licenses")
    val licenses: List<CheckedLicense> = emptyList(),
)

/**
 * A license or trial of a customer, see https://plugins.jetbrains.com/docs/marketplace/check-license.html.
 * The values of the documented enums are kept as strings, the API may add values.
 *
 * @param type The type of the license, e.g. `COMMERCIAL` or `PERSONAL`.
 * @param period The billing period, e.g. `MONTHLY` or `ANNUAL`.
 * @param overuseTill The end of the grace period after [validTill], in which the plugin still works.
 * @param ownerCode The customer code of the owner, e.g. of the organization, which assigned the license.
 * @param ownerType The type of the owner, e.g. `ORGANIZATION` or `PERSON`.
 */
@Serializable
data class CheckedLicense(
    @SerialName("type")
    val type: String? = null,
    @SerialName("trial")
    val isTrial: Boolean? = null,
    @SerialName("period")
    val period: String? = null,
    @SerialName("startFrom")
    @Serializable(LenientDateSerializer::class)
    val startFrom: YearMonthDay? = null,
    @SerialName("validTill")
    @Serializable(LenientDateSerializer::class)
    val validTill: YearMonthDay? = null,
    @SerialName("overuseTill")
    @Serializable(LenientDateSerializer::class)
    val overuseTill: YearMonthDay? = null,
    @SerialName("ownerCode")
    val ownerCode: String? = null,
    @SerialName("ownerType")
    val ownerType: String? = null,
    @SerialName("ownerEmail")
    val ownerEmail: String? = null,
)
