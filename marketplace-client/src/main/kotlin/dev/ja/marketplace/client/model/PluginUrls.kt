/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.PluginUrl
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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