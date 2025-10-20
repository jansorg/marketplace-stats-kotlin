/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.JetBrainsProductId

object JetBrainsProductFamilies {
    val IntelliJPlatform = setOf<JetBrainsProductId>(
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

    val Fleet = setOf<JetBrainsProductId>("FLEET")

    val Resharper = setOf<JetBrainsProductId>("RESHARPER")
}