/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProductRelease(
    @SerialName("date")
    val date: LocalDate,
    @SerialName("type")
    val type: ProductReleaseType,
    @SerialName("printableReleaseType")
    val printableReleaseType: String? = null,
    @SerialName("majorVersion")
    val majorVersion: String,
    @SerialName("version")
    val version: String,
    @SerialName("build")
    val build: BuildNumber? = null,
    @SerialName("notesLink")
    val notesLink: String? = null,
    @SerialName("whatsnew")
    val whatsNewHtml: String? = null,
    @SerialName("licenseRequired")
    val licenseRequired: Boolean? = null,
    @SerialName("patches")
    val patches: Map<ProductPatchType, List<ProductPatchRelease>>,
    @SerialName("downloads")
    val downloads: Map<ProductDownloadType, ProductReleaseDownload>? = null,
    @SerialName("uninstallFeedbackLinks")
    val uninstallFeedbackLinks: Map<String, String>? = null,
    @SerialName("isSecurityCritical")
    val isSecurityCritical: Boolean = false,
)