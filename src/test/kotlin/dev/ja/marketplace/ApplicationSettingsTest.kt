/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import com.github.ajalt.clikt.testing.test
import dev.ja.marketplace.client.ClientLogLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/**
 * The settings of the options, the environment variables and the configuration file: the options override the
 * environment variables, which override the configuration file, which overrides the defaults.
 */
class ApplicationSettingsTest {
    @TempDir
    lateinit var tempDir: Path

    private fun configFile(json: String): Path {
        return Files.writeString(tempDir.resolve("config.json"), json)
    }

    /** Runs the command without starting the server, the system environment variables aren't used. */
    private fun run(args: List<String>, env: Map<String, String>, config: String?): Pair<ApplicationSettings?, String> {
        var settings: ApplicationSettings? = null
        val argv = args + listOfNotNull(config?.let { configFile(it).toString() })
        val result = Application("1.0") { settings = it }.test(argv, envvars = env)
        return settings to result.stderr
    }

    private fun settings(vararg args: String, env: Map<String, String> = emptyMap(), config: String? = null): ApplicationSettings {
        val (settings, stderr) = run(args.toList(), env, config)
        return settings ?: throw AssertionError("No settings: $stderr")
    }

    private fun error(vararg args: String, env: Map<String, String> = emptyMap(), config: String? = null): String {
        val (settings, stderr) = run(args.toList(), env, config)
        assertNull(settings, "the server must not be started")
        return stderr
    }

    @Test
    fun defaults() {
        val settings = settings("--api-key=key")
        assertEquals("key", settings.apiKey)
        assertEquals("0.0.0.0", settings.serverHostname)
        assertEquals(8080, settings.serverPort)
        assertEquals("USD", settings.displayCurrencyCode)
        assertFalse(settings.showResellerCharges)
        assertNull(settings.cacheDir)
        assertNull(settings.autoRefreshInterval)
        assertEquals(10, settings.rateLimit)
        assertNull(settings.customEvents)
        assertTrue(settings.allPluginsSummary)
        assertEquals(ClientLogLevel.None, settings.logLevel)
        assertTrue("localhost" in settings.mcpAllowedHosts!!)
    }

    @Test
    fun apiKey() {
        val config = """{"apiKey": "config-key"}"""
        assertEquals("option-key", settings("--api-key=option-key", env = mapOf("MARKETPLACE_API_KEY" to "env-key"), config = config).apiKey)
        assertEquals("env-key", settings(env = mapOf("MARKETPLACE_API_KEY" to "env-key"), config = config).apiKey)
        assertEquals("config-key", settings(config = config).apiKey)
        assertTrue("No API key provided" in error())
    }

    @Test
    fun displayCurrency() {
        val config = """{"apiKey": "key", "displayedCurrency": "GBP"}"""
        val env = mapOf("MARKETPLACE_DISPLAY_CURRENCY" to "JPY")
        assertEquals("EUR", settings("--currency=EUR", env = env, config = config).displayCurrencyCode)
        assertEquals("JPY", settings(env = env, config = config).displayCurrencyCode)
        assertEquals("GBP", settings(config = config).displayCurrencyCode)
        assertEquals("USD", settings(config = """{"apiKey": "key"}""").displayCurrencyCode)
    }

    @Test
    fun showResellerCharges() {
        val config = """{"apiKey": "key", "showResellerCharges": true}"""
        assertTrue(settings("--api-key=key", "--show-reseller-charges", env = mapOf("MARKETPLACE_SHOW_RESELLER_CHARGES" to "false")).showResellerCharges)
        // the environment variable overrides the configuration file, also with false
        assertFalse(settings(env = mapOf("MARKETPLACE_SHOW_RESELLER_CHARGES" to "false"), config = config).showResellerCharges)
        assertTrue(settings(env = mapOf("MARKETPLACE_SHOW_RESELLER_CHARGES" to "true"), config = """{"apiKey": "key"}""").showResellerCharges)
        assertTrue(settings(config = config).showResellerCharges)
        assertFalse(settings(config = """{"apiKey": "key"}""").showResellerCharges)
    }

    @Test
    fun rateLimit() {
        val config = """{"apiKey": "key", "rateLimit": 5}"""
        val env = mapOf("MARKETPLACE_RATE_LIMIT" to "7")
        assertEquals(3, settings("--rate-limit=3", env = env, config = config).rateLimit)
        assertEquals(7, settings(env = env, config = config).rateLimit)
        assertEquals(5, settings(config = config).rateLimit)
        assertEquals(10, settings(config = """{"apiKey": "key"}""").rateLimit)
        // 0 disables the limit, also if the configuration file has one
        assertEquals(0, settings("--rate-limit=0", config = config).rateLimit)
    }

    @Test
    fun invalidRateLimit() {
        assertTrue("must not be negative" in error("--api-key=key", "--rate-limit=-1"))
        assertTrue("must not be negative" in error(env = mapOf("MARKETPLACE_RATE_LIMIT" to "-1"), config = """{"apiKey": "key"}"""))
        assertTrue("must not be negative" in error(config = """{"apiKey": "key", "rateLimit": -1}"""))
    }

    @Test
    fun cacheDir() {
        val config = """{"apiKey": "key", "cacheDir": "config-cache"}"""
        val env = mapOf("MARKETPLACE_CACHE_DIR" to tempDir.resolve("env-cache").toString())
        val option = tempDir.resolve("option-cache").toString()
        assertEquals(Path.of(option), settings("--cache-dir=$option", env = env, config = config).cacheDir)
        assertEquals(tempDir.resolve("env-cache"), settings(env = env, config = config).cacheDir)
        // a relative path of the configuration file is relative to its directory
        assertEquals(tempDir.resolve("config-cache"), settings(config = config).cacheDir)
        // an empty value disables the cache of the configuration file
        assertNull(settings(env = mapOf("MARKETPLACE_CACHE_DIR" to ""), config = config).cacheDir)
        assertNull(settings(config = """{"apiKey": "key"}""").cacheDir)
    }

    @Test
    fun eventsFile() {
        val events = "events:\n  - plugin: 1\n    date: 2026-01-01\n    label: \"%s\"\n"
        val optionFile = Files.writeString(tempDir.resolve("option.yml"), events.format("option"))
        val envFile = Files.writeString(tempDir.resolve("env.yml"), events.format("env"))
        Files.writeString(tempDir.resolve("config.yml"), events.format("config"))
        val config = """{"apiKey": "key", "eventsFile": "config.yml"}"""
        val env = mapOf("MARKETPLACE_EVENTS_FILE" to envFile.toString())

        assertEquals(optionFile, settings("--events=$optionFile", env = env, config = config).customEvents?.path)
        assertEquals(envFile, settings(env = env, config = config).customEvents?.path)
        // a relative path of the configuration file is relative to its directory
        assertEquals(tempDir.resolve("config.yml"), settings(config = config).customEvents?.path)
        assertTrue("doesn't exist" in error("--events=${tempDir.resolve("missing.yml")}", config = config))
    }

    @Test
    fun autoRefresh() {
        assertNull(settings("--api-key=key").autoRefreshInterval)
        assertEquals(30.minutes, settings("--api-key=key", "--auto-refresh").autoRefreshInterval)
        assertEquals(5.minutes, settings("--api-key=key", "--auto-refresh=5").autoRefreshInterval)
        assertEquals(15.minutes, settings("--api-key=key", env = mapOf("MARKETPLACE_AUTO_REFRESH" to "15")).autoRefreshInterval)
        assertNull(settings("--api-key=key", "--auto-refresh=0").autoRefreshInterval)
    }

    @Test
    fun mcpServer() {
        assertNull(settings("--api-key=key", "--no-mcp").mcpAllowedHosts)
        val hosts = settings("--api-key=key", "--host=stats.example.com", "--mcp-allowed-hosts=other.example.com").mcpAllowedHosts!!
        assertTrue("stats.example.com" in hosts && "other.example.com" in hosts, "$hosts")
        // the wildcard address isn't a hostname
        assertNotEquals(null, settings("--api-key=key").mcpAllowedHosts)
        assertFalse("0.0.0.0" in settings("--api-key=key").mcpAllowedHosts!!)
    }
}
