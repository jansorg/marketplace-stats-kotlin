/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.PluginId

/**
 * Plugin data properties shared by the different types of plugin
 * data JSON structures.
 */
interface PluginInfoBase {
    val id: PluginId
    val name: String
    val link: String?
}