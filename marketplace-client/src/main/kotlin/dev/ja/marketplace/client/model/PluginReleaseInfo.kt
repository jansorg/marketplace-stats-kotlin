/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.*
import dev.ja.marketplace.services.BuildNumberRef
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
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
    val createdTimestamp: Instant,
    @SerialName("file")
    val fileUrlPath: String,
    @SerialName("size")
    val fileSizeBytes: Long,
    @SerialName("notes")
    val notes: String? = null,
    @SerialName("since")
    val since: BuildNumberRef? = null,
    @SerialName("until")
    val until: BuildNumberRef? = null,
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
    override fun toString(): String {
        return "PluginReleaseInfo(id=$id, pluginId=$pluginId)"
    }

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

    val channelNameOrStable: String
        get() {
            return channel.ifBlank { "stable" }
        }

    /**
     * The base filename of the uploaded file.
     */
    val updateFilename: String
        get() {
            return fileUrlPath.substring(fileUrlPath.lastIndexOf('/') + 1)
        }
}