/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.*
import io.ktor.http.*
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginReleaseInfo(
    @SerialName("id")
    val id: PluginReleaseId,
    @SerialName("pluginId")
    val pluginId: PluginId,
    /* URL path of the HTML displaying the update details */
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

    /**
     * The base filename of the uploaded file.
     */
    val updateFilename: String
        get() {
            return fileUrlPath.substring(fileUrlPath.lastIndexOf('/') + 1)
        }
}