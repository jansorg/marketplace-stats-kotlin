/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProductFamily(@Transient val jsonId: String) {
    @SerialName("intellij")
    IntelliJ("intellij"),

    @SerialName("teamcity")
    TeamCity("teamcity"),

    @SerialName("teamcity_recipes")
    TeamCityRecipes("teamcity"),

    @SerialName("hub")
    Hub("hub"),

    @SerialName("fleet")
    Fleet("fleet"),

    @SerialName("dotnet")
    DotNet("dotnet"),

    @SerialName("space")
    Space("space"),

    @SerialName("toolbox")
    ToolBox("toolbox"),

    @SerialName("edu")
    Edu("edu"),

    @SerialName("youtrack")
    YouTrack("youtrack"),
}