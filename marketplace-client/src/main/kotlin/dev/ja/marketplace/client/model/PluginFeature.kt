/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A feature of a plugin, which the Marketplace extracts from its releases, e.g. a supported file type or a type of
 * run configurations. The IDEs use the features to suggest plugins, e.g. for a file with an unknown file type.
 */
@Serializable
data class PluginFeature(
    @SerialName("id")
    val id: Int,
    @SerialName("plugin")
    val plugin: PluginInfoShort,
    /**
     * The implementation of the feature, e.g. the pattern of the file names of a file type like `*.sh` or `.bashrc`,
     * or the name of a type of run configurations.
     */
    @SerialName("implName")
    val implementationName: String,
    /** The type of the feature, e.g. [FILE_TYPE] or [CONFIGURATION_TYPE]. */
    @SerialName("featureTypeName")
    val featureType: String,
    @SerialName("priority")
    val priority: Int = 0,
    /** `true` if the Marketplace approved the feature, only approved features are used by the IDEs. */
    @SerialName("approved")
    val approved: Boolean = true,
    @SerialName("blacklisted")
    val blacklisted: Boolean = false,
) {
    val isFileType: Boolean
        get() = featureType == FILE_TYPE

    companion object {
        /** The feature type of the supported file types, the implementation is a pattern of file names, e.g. `*.sh`. */
        const val FILE_TYPE: String = "com.intellij.fileTypeFactory"

        /** The feature type of the types of run configurations, the implementation is the name of the type. */
        const val CONFIGURATION_TYPE: String = "com.intellij.configurationType"
    }
}
