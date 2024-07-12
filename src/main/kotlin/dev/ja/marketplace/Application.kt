/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.ja.marketplace.client.CachingMarketplaceClient
import dev.ja.marketplace.client.ClientLogLevel
import dev.ja.marketplace.client.KtorMarketplaceClient
import dev.ja.marketplace.services.KtorJetBrainsServiceClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files

class Application(version: String) : CliktCommand(
    name = "marketplace-stats",
    help = "Marketplace Stats provides reports for plugins hosted on the JetBrains Marketplace.",
) {
    init {
        versionOption(version)
        context {
            helpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) }
        }
    }

    private val applicationConfig: ApplicationConfig? by argument("config.json file path")
        .help("Path to the application configuration JSON file. It's used as fallback for the other command line options. A template is available at https://github.com/jansorg/marketplace-stats-kotlin/blob/main/config-template.json.")
        .path(mustExist = true, canBeDir = false, mustBeReadable = true)
        .convert { Json.decodeFromString<ApplicationConfig>(Files.readString(it)) }
        .optional()

    private val apiKey: String? by option("-k", "--api-key", envvar = "MARKETPLACE_API_KEY")
        .help("API key for the JetBrains Marketplace. The key is used to find available plugins and to load the data needed to generate a plugin report.")

    private val serverHostname: String by option("-h", "--host", envvar = "MARKETPLACE_SERVER_HOSTNAME")
        .default("0.0.0.0")
        .help("IP address or hostname the integrated webserver is bound to.")

    private val serverPort: Int by option("-p", "--port", envvar = "MARKETPLACE_SERVER_PORT").int()
        .default(8080)
        .help("Port used by the integrated webserver.")

    private val displayCurrency: String? by option("-c", "--currency", envvar = "MARKETPLACE_DISPLAY_CURRENCY")
        .help("Currency for the displayed monetary amounts.")

    private val logging: ClientLogLevel by option(
        "-d",
        "--debug",
        envvar = "MARKETPLACE_LOG_LEVEL"
    ).enum<ClientLogLevel>(key = { it.name.lowercase() })
        .default(ClientLogLevel.None)
        .help("The log level used for the server and the API requests to the marketplace")

    override fun run() {
        val apiKey = this.apiKey
            ?: applicationConfig?.marketplaceApiKey
            ?: throw BadParameterValue("No API key provided. Please refer to --help how to provide it.")

        val displayCurrencyCode = this.displayCurrency
            ?: applicationConfig?.displayedCurrency
            ?: "USD"

        runBlocking {
            val server = MarketplaceStatsServer(
                CachingMarketplaceClient(KtorMarketplaceClient(apiKey = apiKey, logLevel = logging)),
                KtorJetBrainsServiceClient(logLevel = logging),
                serverHostname,
                serverPort,
                ServerConfiguration(displayCurrencyCode)
            )

            server.start()
        }
    }
}

fun main(args: Array<String>) {
    Application(BuildConfig.APP_VERSION).main(args)
}
