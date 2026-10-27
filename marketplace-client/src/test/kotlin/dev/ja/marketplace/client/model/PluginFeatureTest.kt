/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PluginFeatureTest {
    // the model contains all properties of the response
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun responseOfFeatures() {
        val features = json.decodeFromString<List<PluginFeature>>(
            """
            [
                {"id": 4920, "plugin": {"id": 13841, "name": "BashSupport Pro", "link": "/plugin/13841-bashsupport-pro"},
                 "implName": "BashConfigurationType", "featureTypeName": "com.intellij.configurationType", "priority": 0, "approved": true, "blacklisted": false},
                {"id": 4869, "plugin": {"id": 13841, "name": "BashSupport Pro", "link": "/plugin/13841-bashsupport-pro"},
                 "implName": "*.sh", "featureTypeName": "com.intellij.fileTypeFactory", "priority": 0, "approved": true, "blacklisted": false},
                {"id": 4650, "plugin": {"id": 13841, "name": "BashSupport Pro", "link": "/plugin/13841-bashsupport-pro"},
                 "implName": ".bashrc", "featureTypeName": "com.intellij.fileTypeFactory", "priority": 0, "approved": true, "blacklisted": false}
            ]
            """
        )

        assertEquals(listOf("*.sh", ".bashrc"), features.filter(PluginFeature::isFileType).map(PluginFeature::implementationName))
        assertEquals(PluginFeature.CONFIGURATION_TYPE, features.first().featureType)
        assertEquals(13841, features.first().plugin.id)
    }
}
