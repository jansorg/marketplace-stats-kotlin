/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

interface PluginPageDefinition {
    suspend fun createTemplateParameters(): Map<String, Any?>
}