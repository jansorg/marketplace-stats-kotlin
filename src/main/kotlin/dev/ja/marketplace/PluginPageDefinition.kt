/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import io.ktor.server.request.*

interface PluginPageDefinition {
    suspend fun createTemplateParameters(dataLoader: PluginDataLoader, request: ApplicationRequest): Map<String, Any?>
}