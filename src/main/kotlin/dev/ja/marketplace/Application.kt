/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.KtorMarketplaceClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

object Application {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: path/to/config.json")
            exitProcess(1)
        }

        val config = Json.decodeFromString<ApplicationConfig>(Files.readString(Path.of(args[0])))
        val client = KtorMarketplaceClient(config.marketplaceApiKey)

        runBlocking {
            val server = MarketplaceStatsServer(config.pluginId, client)
            server.start()
        }
    }
}