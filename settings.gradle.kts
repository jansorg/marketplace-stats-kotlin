/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

plugins {
    // Downloads the JDK of the Gradle toolchain (see jvmToolchain in build.gradle.kts) if it isn't installed locally
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "marketplace-stats-kotlin"

include("marketplace-client")
include("marketplace-data")
