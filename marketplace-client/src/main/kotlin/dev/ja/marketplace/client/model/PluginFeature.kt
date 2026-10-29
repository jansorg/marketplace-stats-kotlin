/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.PluginIdOrInfoSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * A feature of a plugin, which the Marketplace extracts from its releases, e.g. a supported file type or a type of
 * run configurations. The IDEs use the features to suggest plugins, e.g. for a file with an unknown file type.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PluginFeature(
    @SerialName("id")
    val id: Int,
    /** The API returns the plugin with its name and link, only its ID is kept, see [PluginIdOrInfoSerializer]. */
    @SerialName("pluginId")
    @JsonNames("plugin")
    @Serializable(PluginIdOrInfoSerializer::class)
    val pluginId: PluginId,
    /**
     * The implementation of the feature, e.g. the pattern of the file names of a file type like `*.sh` or `.bashrc`,
     * or the name of a type of run configurations.
     */
    @SerialName("implName")
    val implementationName: String,
    /**
     * The type of the feature, e.g. [FILE_TYPE] or [CONFIGURATION_TYPE], see [FEATURE_TYPES] for the known types.
     * The Marketplace may add types, which aren't known.
     */
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

    val isConfigurationType: Boolean
        get() = featureType == CONFIGURATION_TYPE

    val isDependencySupport: Boolean
        get() = featureType == DEPENDENCY_SUPPORT

    companion object {
        /** The feature type of the supported file types, the implementation is a pattern of file names, e.g. `*.sh`. */
        const val FILE_TYPE: String = "com.intellij.fileTypeFactory"

        /** The feature type of the types of run configurations, the implementation is the name of the type. */
        const val CONFIGURATION_TYPE: String = "com.intellij.configurationType"

        /** The feature type of the types of facets, the implementation is the ID of the facet type, e.g. `django`. */
        const val FACET_TYPE: String = "com.intellij.facetType"

        /** The feature type of the types of modules, the implementation is the ID of the module type. */
        const val MODULE_TYPE: String = "com.intellij.moduleType"

        /** The feature type of the types of artifacts, the implementation is the ID of the artifact type, e.g. `war`. */
        const val ARTIFACT_TYPE: String = "com.intellij.packaging.artifacts.ArtifactType"

        /**
         * The feature type of the supported dependencies of projects, the implementation is `<kind>:<coordinate>`,
         * e.g. a library like `dotnet:Confluent.Kafka` or an installed program like `executable:docker`.
         */
        const val DEPENDENCY_SUPPORT: String = "dependencySupport"

        /** The feature types, which the Marketplace extracts from the releases of plugins. */
        val FEATURE_TYPES: List<String> = listOf(FILE_TYPE, CONFIGURATION_TYPE, FACET_TYPE, MODULE_TYPE, ARTIFACT_TYPE, DEPENDENCY_SUPPORT)
    }
}
