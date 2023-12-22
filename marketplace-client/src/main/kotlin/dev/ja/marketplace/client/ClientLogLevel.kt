/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.plugins.logging.*

enum class ClientLogLevel(internal val ktorLogLevel: LogLevel) {
    None(LogLevel.NONE),
    Normal(LogLevel.INFO),
    Verbose(LogLevel.HEADERS)
}