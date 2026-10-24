/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.ja.marketplace.client.CaffeineCacheMarketplaceClient
import dev.ja.marketplace.client.ClientLogLevel
import dev.ja.marketplace.client.persistentCache.PersistentCacheStore
import dev.ja.marketplace.exchangeRate.EcbExchangeRateData
import dev.ja.marketplace.mcp.McpDefaultAllowedHosts
import dev.ja.marketplace.mcp.normalizeMcpAllowedHost
import dev.ja.marketplace.services.CachingJetBrainsServiceClient
import dev.ja.marketplace.services.KtorJetBrainsServiceClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class Application(version: String) : CliktCommand(name = "marketplace-stats") {
    override fun help(context: Context): String {
        return "Marketplace Stats provides reports for plugins hosted on the JetBrains Marketplace."
    }

    init {
        versionOption(version)
        context {
            helpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) }
        }
    }

    /** The configuration and the path of its file, which is needed to resolve relative paths of the configuration. */
    private val applicationConfigFile: Pair<ApplicationConfig, Path>? by argument("config.json file path")
        .help("Path to the application configuration JSON file. It's used as fallback for the other command line options. A template is available at https://github.com/jansorg/marketplace-stats-kotlin/blob/main/config-template.json.")
        .path(mustExist = true, canBeDir = false, mustBeReadable = true)
        .convert { Json.decodeFromString<ApplicationConfig>(Files.readString(it)) to it }
        .optional()

    private val applicationConfig: ApplicationConfig?
        get() = applicationConfigFile?.first

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

    private val showResellerCharges: Boolean? by option("--show-reseller-charges", envvar = "MARKETPLACE_SHOW_RESELLER_CHARGES")
        .nullableFlag()
        .help("Show incorrect charges for sales through resellers.")

    private val cacheDir: String? by option("--cache-dir", envvar = "MARKETPLACE_CACHE_DIR")
        .help("Directory of the persistent cache. Sales, trials, prices and exchange rates are stored there to speed up restarts. The data is kept in memory only if no directory is set or if it's empty. A leading ~ is the home directory.")

    private val mcpEnabled: Boolean by option("--mcp", envvar = "MARKETPLACE_MCP")
        .flag("--no-mcp", default = true, defaultForHelp = "enabled")
        .help("Serve the MCP server for AI agents at /mcp of the integrated webserver.")

    private val allPluginsSummary: Boolean by option("--all-plugins-summary", envvar = "MARKETPLACE_ALL_PLUGINS_SUMMARY")
        .flag("--no-all-plugins-summary", default = true, defaultForHelp = "enabled")
        .help("Show the sales and downloads of all plugins above the list of plugins. The data of all plugins is loaded at the start, or taken from the persistent cache.")

    private val mcpAllowedHosts: List<String> by option("--mcp-allowed-hosts", envvar = "MARKETPLACE_MCP_ALLOWED_HOSTS")
        .split(",")
        .default(emptyList(), defaultForHelp = "none")
        .help("Comma-separated hostnames, which are accepted in the Host and Origin headers of requests to the MCP server, in addition to localhost, 127.0.0.1, [::1] and the hostname of --host. Protects against DNS rebinding attacks.")

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

        val showResellerCharges = this.showResellerCharges
            ?: applicationConfig?.showResellerCharges
            ?: false

        // an empty value, e.g. MARKETPLACE_CACHE_DIR= , disables the cache of the Docker image.
        // Relative paths of the configuration file are relative to its directory, other paths to the working directory.
        val cacheDir = when (val option = this.cacheDir) {
            null -> applicationConfigFile?.let { (config, file) ->
                config.cacheDir?.let { cacheDirectory(it, file.toAbsolutePath().parent) }
            }

            else -> cacheDirectory(option, baseDirectory = null)
        }
        if (cacheDir != null && Files.exists(cacheDir) && !Files.isDirectory(cacheDir)) {
            throw BadParameterValue("The cache directory $cacheDir is a file.")
        }
        if (cacheDir != null) {
            EcbExchangeRateData.Default.enablePersistentCache(PersistentCacheStore(cacheDir))
        }
        // the wildcard addresses accept connections for any hostname, they can't be used to validate the Host header
        val mcpAllowedHosts = (McpDefaultAllowedHosts + serverHostname.takeUnless { it == "0.0.0.0" || it == "::" } + mcpAllowedHosts)
            .filterNotNull()
            .mapNotNull(::normalizeMcpAllowedHost)
            .distinct()

        runBlocking {
            val server = MarketplaceStatsServer(
                CaffeineCacheMarketplaceClient(apiKey = apiKey, logLevel = logging, persistentCacheDirectory = cacheDir),
                // the products of JetBrains are the same for all accounts, they're stored outside the directory of the account
                CachingJetBrainsServiceClient(KtorJetBrainsServiceClient(logLevel = logging), cacheDir?.let { PersistentCacheStore(it).subStore("jetbrains") }),
                serverHostname,
                serverPort,
                ServerConfiguration(displayCurrencyCode, showResellerCharges, allPluginsSummary),
                mcpAllowedHosts.takeIf { mcpEnabled },
            )

            server.start()
        }
    }
}

fun main(args: Array<String>) {
    Application(BuildConfig.APP_VERSION).main(args)
}
