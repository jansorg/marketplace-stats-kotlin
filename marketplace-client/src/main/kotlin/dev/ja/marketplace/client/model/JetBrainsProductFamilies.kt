/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.JetBrainsProductId

@Suppress("unused")
object JetBrainsProductFamilies {
    val IntelliJPlatform: Set<JetBrainsProductId> = setOf(
        "ANDROID_STUDIO",
        "APPCODE",
        "AQUA",
        "CLION",
        "CWMGUEST",
        "DATASPELL",
        "DBE",
        "GOLAND",
        "IDEA",
        "IDEA_COMMUNITY",
        "JBCLIENT",
        "MPS",
        "PHPSTORM",
        "PYCHARM",
        "PYCHARM_COMMUNITY",
        "RUBYMINE",
        "RUST",
        "WEBSTORM",
        "WRITERSIDE",
    )

    val IntelliJPlatformWithRider = IntelliJPlatform + "RIDER"

    val Fleet: Set<JetBrainsProductId> = setOf("FLEET")

    val Resharper: Set<JetBrainsProductId> = setOf("RESHARPER")
}