/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.Serializable

@Serializable
enum class PluginSearchProductId(val parameterValue: String) {
    ANDROIDSTUDIO("androidstudio"),
    APPCODE("appcode"),
    AQUA("aqua"),
    CLION("clion"),
    DATASPELL("dataspell"),
    DBE("dbe"),
    GO("go"),
    IDEA("idea"),
    IDEA_CE("idea_ce"),
    MPS("mps"),
    PHPSTORM("phpstorm"),
    PYCHARM("pycharm"),
    PYCHARM_CE("pycharm_ce"),
    RIDER("rider"),
    RUBY("ruby"),
    RUST("rust"),
    WEBSTORM("webstorm"),
    WRITERSIDE("writerside"),
}