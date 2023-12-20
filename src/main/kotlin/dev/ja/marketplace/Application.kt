/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.CachingMarketplaceClient
import dev.ja.marketplace.client.KtorMarketplaceClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

object Application {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = createConfig(args)
        if (config == null) {
            println("Usage: path/to/config.json")
            exitProcess(1)
        }

        val client = CachingMarketplaceClient(KtorMarketplaceClient(config.marketplaceApiKey))

        runBlocking {
            val server = MarketplaceStatsServer(client)
            server.start()
        }
    }

    private fun createConfig(args: Array<String>): ApplicationConfig? {
        val envApiKey = System.getenv("MARKETPLACE_API_KEY")?.trim()
        if (!envApiKey.isNullOrEmpty()) {
            return ApplicationConfig(envApiKey)
        }

        return when {
            args.isEmpty() -> null
            else -> Json.decodeFromString<ApplicationConfig>(Files.readString(Path.of(args[0])))
        }

    }
}