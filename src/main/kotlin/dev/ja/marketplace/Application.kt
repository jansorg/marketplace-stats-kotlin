/*
 * Copyright (c) 2023-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.ja.marketplace.client.*
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The command line of the application. The options override the environment variables, which override the
 * configuration file, which overrides the defaults.
 *
 * @param startServer Starts the server with the resolved settings, tests only check the settings.
 */
class Application internal constructor(
    version: String,
    private val startServer: (ApplicationSettings) -> Unit,
) : CliktCommand(name = "marketplace-stats") {
    constructor(version: String) : this(version, ::startServer)

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

    // the options with a fallback to the configuration file have no default value, but the help shows the default
    private val displayCurrency: String? by option(
        "-c",
        "--currency",
        envvar = "MARKETPLACE_DISPLAY_CURRENCY",
        helpTags = mapOf(HelpFormatter.Tags.DEFAULT to DEFAULT_DISPLAY_CURRENCY),
    )
        .help("Currency for the displayed monetary amounts.")

    private val showResellerCharges: Boolean? by option(
        "--show-reseller-charges",
        envvar = "MARKETPLACE_SHOW_RESELLER_CHARGES",
        helpTags = mapOf(HelpFormatter.Tags.DEFAULT to "disabled"),
    )
        .nullableFlag()
        .help("Show incorrect charges for sales through resellers.")

    private val cacheDir: String? by option(
        "--cache-dir",
        envvar = "MARKETPLACE_CACHE_DIR",
        helpTags = mapOf(HelpFormatter.Tags.DEFAULT to "none"),
    )
        .help("Directory of the persistent cache. Sales, trials, prices and exchange rates are stored there to speed up restarts. The data is kept in memory only if no directory is set or if it's empty. A leading ~ is the home directory.")

    private val autoRefreshMinutes: Int? by option(
        "--auto-refresh",
        envvar = "MARKETPLACE_AUTO_REFRESH",
        metavar = "MINUTES",
        helpTags = mapOf(HelpFormatter.Tags.DEFAULT to "disabled"),
    )
        .int()
        .optionalValue(DEFAULT_AUTO_REFRESH_MINUTES, acceptsUnattachedValue = false)
        .help(
            "Refreshes the data of the plugins, which were requested in the last 24 hours, in the background every MINUTES " +
                    "(default: $DEFAULT_AUTO_REFRESH_MINUTES), so that the pages don't wait for the Marketplace. " +
                    "Use --auto-refresh or --auto-refresh=MINUTES, 0 disables it."
        )
        .check("The interval of the automatic refresh must not be negative") { it >= 0 }

    private val rateLimit: Int? by option(
        "--rate-limit",
        envvar = "MARKETPLACE_RATE_LIMIT",
        metavar = "REQUESTS",
        helpTags = mapOf(HelpFormatter.Tags.DEFAULT to DEFAULT_RATE_LIMIT.toString()),
    )
        .int()
        .help("The maximum number of requests per second to each server, e.g. the Marketplace, requests beyond it wait. 0 disables the limit.")
        .check("The rate limit must not be negative") { it >= 0 }

    private val eventsFile: String? by option(
        "--events",
        envvar = "MARKETPLACE_EVENTS_FILE",
        metavar = "FILE",
        helpTags = mapOf(HelpFormatter.Tags.DEFAULT to "none"),
    )
        .help(
            "YAML file with custom events, which the charts can show as vertical lines, e.g. blog posts or changes of the " +
                    "trial period. The file is read again when it's modified. See README.md for the format."
        )

    private val mcpEnabled: Boolean by option("--mcp", envvar = "MARKETPLACE_MCP")
        .flag("--no-mcp", default = true, defaultForHelp = "enabled")
        .help("Serve the MCP server for AI agents at /mcp of the integrated webserver.")

    private val allPluginsSummary: Boolean by option("--all-plugins-summary", envvar = "MARKETPLACE_ALL_PLUGINS_SUMMARY")
        .flag("--no-all-plugins-summary", default = true, defaultForHelp = "enabled")
        .help("Show the sales and downloads of all paid plugins above the list of plugins. The data of these plugins is loaded at the start, or taken from the persistent cache.")

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
        startServer(settings())
    }

    /**
     * @return The settings of the options, of the environment variables and of the configuration file.
     */
    internal fun settings(): ApplicationSettings {
        val apiKey = this.apiKey
            ?: applicationConfig?.marketplaceApiKey
            ?: throw BadParameterValue("No API key provided. Please refer to --help how to provide it.")

        val displayCurrencyCode = this.displayCurrency
            ?: applicationConfig?.displayedCurrency
            ?: DEFAULT_DISPLAY_CURRENCY

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

        return ApplicationSettings(
            apiKey = apiKey,
            serverHostname = serverHostname,
            serverPort = serverPort,
            displayCurrencyCode = displayCurrencyCode,
            showResellerCharges = showResellerCharges,
            cacheDir = cacheDir,
            autoRefreshInterval = autoRefreshMinutes?.takeIf { it > 0 }?.minutes,
            rateLimit = findRateLimit(),
            customEvents = loadEventsConfiguration(),
            mcpAllowedHosts = createMcpAllowedHosts().takeIf { mcpEnabled },
            allPluginsSummary = allPluginsSummary,
            logLevel = logging,
        )
    }

    private fun createMcpAllowedHosts(): List<String> {
        return buildList {
            addAll(McpDefaultAllowedHosts)
            add(serverHostname.takeUnless { it == "0.0.0.0" || it == "::" })
            addAll(mcpAllowedHosts)
        }
            .filterNotNull()
            .mapNotNull(::normalizeMcpAllowedHost)
            .distinct()
    }

    private fun loadEventsConfiguration(): CustomEventsFile? {
        // like the cache directory, a relative path of the configuration file is relative to its directory
        val eventsPath = when (val option = this.eventsFile) {
            null -> applicationConfigFile?.let { (config, file) ->
                config.eventsFile?.let { configuredPath(it, file.toAbsolutePath().parent) }
            }

            else -> configuredPath(option, baseDirectory = null)
        }

        return eventsPath?.let { path ->
            if (!Files.isRegularFile(path)) {
                throw BadParameterValue("The events file $path doesn't exist.")
            }

            CustomEventsFile(path).also {
                try {
                    it.load()
                } catch (e: IllegalArgumentException) {
                    throw BadParameterValue(e.message ?: "The events file $path is invalid.")
                }
            }
        }
    }

    private fun findRateLimit(): Int {
        val rateLimit = this.rateLimit
            ?: applicationConfig?.rateLimit
            ?: DEFAULT_RATE_LIMIT
        if (rateLimit < 0) {
            throw BadParameterValue("The rate limit must not be negative: $rateLimit")
        }
        return rateLimit
    }
}

/**
 * The settings of the application, see [Application.settings].
 *
 * @param rateLimit The maximum number of requests per second to each server, 0 without a limit.
 * @param mcpAllowedHosts The hostnames of the MCP server, `null` if it's disabled.
 */
internal data class ApplicationSettings(
    val apiKey: String,
    val serverHostname: String,
    val serverPort: Int,
    val displayCurrencyCode: String,
    val showResellerCharges: Boolean,
    val cacheDir: Path?,
    val autoRefreshInterval: Duration?,
    val rateLimit: Int,
    val customEvents: CustomEventsFile?,
    val mcpAllowedHosts: List<String>?,
    val allPluginsSummary: Boolean,
    val logLevel: ClientLogLevel,
)

/**
 * Starts the server with the [settings] and waits until it stopped.
 */
internal fun startServer(settings: ApplicationSettings) {
    val httpClientBuilder = createDefaultHttpClientBuilder(settings.logLevel, settings.rateLimit)
    val cachingStrategy = CachingStrategy(autoRefreshInterval = settings.autoRefreshInterval)
    val cacheStore = settings.cacheDir?.let(::PersistentCacheStore)
    val ecbRates = createEcbRatesProvider(httpClientBuilder, cacheStore)
    val client = createMarketplaceClient(settings.apiKey, httpClientBuilder, cacheStore, cachingStrategy)
    val servicesClient = createJetBrainsServiceClient(cacheStore, httpClientBuilder)

    // the clients and their connections are closed after the web server stopped
    client.use {
        servicesClient.use {
            ecbRates.use {
                runBlocking {
                    val server = MarketplaceStatsServer(
                        client,
                        servicesClient,
                        settings.serverHostname,
                        settings.serverPort,
                        ServerConfiguration(settings.displayCurrencyCode, settings.showResellerCharges, settings.allPluginsSummary, settings.customEvents),
                        settings.mcpAllowedHosts,
                        PluginAutoRefresh(client, cachingStrategy).takeIf { cachingStrategy.autoRefreshInterval != null },
                        ecbRates,
                    )

                    server.start()
                }
            }
        }
    }
}

private fun createDefaultHttpClientBuilder(logLevel: ClientLogLevel, rateLimit: Int): HttpClientBuilder {
    return HttpClientFactory.builder()
        .withLogLevel(logLevel)
        .withUserAgent("${HttpClientFactory.DEFAULT_USER_AGENT}/${BuildConfig.APP_VERSION}")
        .let { if (rateLimit > 0) it.withRateLimit(rateLimit, 1.seconds) else it }
}

/**
 * The exchange rates of all currencies, loaded once. The stored rates are validated with a conditional request.
 */
private fun createEcbRatesProvider(httpClientBuilder: HttpClientBuilder, cacheStore: PersistentCacheStore?): EcbExchangeRateData {
    val ecbRates = EcbExchangeRateData(httpClientBuilder = httpClientBuilder)
    cacheStore?.let(ecbRates::enablePersistentCache)
    return ecbRates
}

private fun createMarketplaceClient(
    apiKey: String,
    httpClientBuilder: HttpClientBuilder,
    cacheStore: PersistentCacheStore?,
    cachingStrategy: CachingStrategy
): CaffeineCacheMarketplaceClient {
    return CaffeineCacheMarketplaceClient(
        apiKey = apiKey,
        httpClientBuilder = httpClientBuilder,
        persistentCache = cacheStore?.accountStore(Marketplace.HOSTNAME, apiKey),
        cachingStrategy = cachingStrategy,
    )
}

private fun createJetBrainsServiceClient(
    cacheStore: PersistentCacheStore?,
    httpClientBuilder: HttpClientBuilder
): CachingJetBrainsServiceClient {
    // the products of JetBrains are the same for all accounts, they're stored outside the directory of the account.
    // The services support conditional requests, the HTTP cache validates the stored responses after a day, the
    // client keeps them in memory for a day.
    val servicesStore = cacheStore?.subStore("jetbrains")
    return CachingJetBrainsServiceClient(
        KtorJetBrainsServiceClient(httpClientBuilder.withHttpCacheDirectory(servicesStore?.directory?.resolve("http"))),
    )
}

private const val DEFAULT_AUTO_REFRESH_MINUTES = 30

private const val DEFAULT_DISPLAY_CURRENCY = "USD"

/** The requests per second to each server, few enough to not burden the Marketplace, enough to load the history of a plugin quickly. */
private const val DEFAULT_RATE_LIMIT = 10

fun main(args: Array<String>) {
    // the chart images of the MCP server are drawn with AWT, the server never opens windows
    if (System.getProperty("java.awt.headless") == null) {
        System.setProperty("java.awt.headless", "true")
    }
    Application(BuildConfig.APP_VERSION).main(args)
}
