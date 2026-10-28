/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.PluginReleaseId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The kinds of verifications of the releases of a plugin, see [dev.ja.marketplace.client.MarketplaceClient.verificationResults].
 */
enum class PluginVerificationType(val requestParameter: String) {
    IntelliJCompatibility("INTELLIJ_COMPATIBILITY"),
    IdePerformance("IDE_PERFORMANCE"),
    ReSharperCompatibility("RESHARPER_COMPATIBILITY"),
}

/**
 * The result of a verification of a release of a plugin with an IDE, e.g. by the IntelliJ Plugin Verifier.
 * The values of enums of the API are strings, the API may add values.
 *
 * @param resultType The kind of the result, e.g. `OK`, `PROBLEMS` or `CRITICAL`.
 * @param verificationVerdict A summary of the result, e.g. the number of problems.
 * @param verificationDateMillis The time of the verification in milliseconds since the epoch.
 * @param fullVerificationResultUrl The URL of the complete report.
 */
@Serializable
data class PluginVerificationResult(
    @SerialName("resultId")
    val resultId: Long,
    @SerialName("updateId")
    val pluginReleaseId: PluginReleaseId,
    @SerialName("verificationType")
    val verificationType: String,
    @SerialName("resultType")
    val resultType: String,
    @SerialName("verificationVerdict")
    val verificationVerdict: String? = null,
    @SerialName("verificationDate")
    val verificationDateMillis: Long? = null,
    @SerialName("verifierVersion")
    val verifierVersion: String? = null,
    @SerialName("availableProduct")
    val product: VerifiedProduct? = null,
    @SerialName("fullVerificationResultUrl")
    val fullVerificationResultUrl: String? = null,
    @SerialName("handled")
    val isHandled: Boolean? = null,
    @SerialName("isSarif")
    val isSarif: Boolean? = null,
    @SerialName("manual")
    val isManual: Boolean? = null,
    @SerialName("scheduled")
    val isScheduled: Boolean? = null,
)

/**
 * The IDE of a verification, see [PluginVerificationResult].
 *
 * @param product The product code, e.g. `IU`.
 * @param type The kind of the release, e.g. `release` or `eap`.
 */
@Serializable
data class VerifiedProduct(
    @SerialName("product")
    val product: String,
    @SerialName("productName")
    val productName: String? = null,
    @SerialName("ideVersion")
    val ideVersion: String? = null,
    @SerialName("fullPresentableVersion")
    val fullPresentableVersion: String? = null,
    @SerialName("shortPresentableVersion")
    val shortPresentableVersion: String? = null,
    @SerialName("releaseVersion")
    val releaseVersion: String? = null,
    @SerialName("isRelease")
    val isRelease: Boolean? = null,
    @SerialName("type")
    val type: String? = null,
)
