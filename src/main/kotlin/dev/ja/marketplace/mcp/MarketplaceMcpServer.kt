/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.mcp

import dev.ja.marketplace.BuildConfig
import io.ktor.server.application.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.Base64

private val logger = LoggerFactory.getLogger("dev.ja.marketplace.mcp")

/**
 * Instructions of the server with the conventions of all tools, which are sent once instead of in each tool description.
 */
internal const val McpServerInstructions =
    "Sales, license, trial and download statistics of the plugins of a JetBrains Marketplace account. " +
            "Call list_plugins first for the plugin IDs. The first call for a plugin loads its data and may be slow, " +
            "the data is cached, refresh_data reloads it.\n" +
            "Conventions:\n" +
            "- Dates are YYYY-MM-DD. startDate and endDate are inclusive and optional, a missing date doesn't limit the range. " +
            "Results contain the range of the returned data, and latestSaleDate/latestTrialDate show how recent the data is.\n" +
            "- Amounts are plain numbers in the result's currency (the server's currency, or the currency argument, " +
            "converted with the ECB rate of the day of each sale). amountUSD is the amount in USD, customerAmount/customerCurrency " +
            "what the customer paid, both only if they differ. fee is the Marketplace fee, the paid amount is amount - fee. " +
            "Percentages are numbers in percent.\n" +
            "- Enum values are lower camel case, e.g. individual, organization, annual, monthly, new, renew. " +
            "Arguments are case-insensitive, unknown arguments are errors.\n" +
            "- Lists accept limit and offset and return totalCount and truncated.\n" +
            "- Table results have columns (key, title, unit, description) and sections with rows as arrays in the order of the columns " +
            "and an optional footer. incompleteRows has the indexes of rows of the current period, values which may still change are " +
            "{value, incomplete: true}, churn rates are {percent, churned, previousActive}, null means no value. " +
            "notes has the details of cells, e.g. counts behind a rate."

/**
 * Hostnames, which are always accepted in the `Host` header of requests to the MCP endpoint.
 */
val McpDefaultAllowedHosts: List<String> = listOf("localhost", "127.0.0.1", "[::1]")

/**
 * The SDK compares the allowed hosts with the hostname of the `Host` header, without the port.
 * IPv6 addresses are enclosed in brackets in the `Host` header.
 *
 * @return The hostname of an allowed host as the SDK expects it, e.g. `nas` for `nas:8080`, `[::1]` for `::1`
 * or `[::1]:8080`, or `null` if it's empty.
 */
fun normalizeMcpAllowedHost(host: String): String? {
    val value = host.trim().lowercase()
    return when {
        value.isEmpty() -> null
        value.startsWith("[") -> value.substringBefore("]") + "]"
        // a single colon separates the port, IPv6 addresses have several colons
        value.count { it == ':' } == 1 -> value.substringBefore(':').takeIf(String::isNotEmpty)
        ':' in value -> "[$value]"
        else -> value
    }
}

/**
 * Serves the MCP tools with the stateless Streamable HTTP transport at [path].
 *
 * The tools only answer requests, so the server needs no sessions. The SDK keeps a session until the client
 * deletes it, which many clients don't do, and each session kept a server with all tools in memory.
 *
 * @param allowedHosts Hostnames accepted in the `Host` and `Origin` headers, which protects against DNS rebinding attacks.
 */
fun Application.marketplaceMcp(tools: MarketplaceMcpTools, allowedHosts: List<String>, path: String = "/mcp") {
    mcpStatelessStreamableHttp(
        path = path,
        allowedHosts = allowedHosts,
        allowedOrigins = allowedHosts.map { "http://$it" },
    ) {
        createMcpServer(tools)
    }
}

fun createMcpServer(tools: MarketplaceMcpTools): Server {
    val server = Server(
        Implementation(name = "marketplace-stats", version = BuildConfig.APP_VERSION.trim()),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        McpServerInstructions,
    )

    for (tool in tools.tools) {
        server.addTool(
            name = tool.name,
            title = tool.title,
            description = tool.description,
            inputSchema = ToolSchema(properties = tool.properties, required = tool.required),
            toolAnnotations = ToolAnnotations(readOnlyHint = tool.readOnly, destructiveHint = false, idempotentHint = true, openWorldHint = false),
        ) { request ->
            callTool(tools, tool.name, request.arguments ?: JsonObject(emptyMap()))
        }
    }

    return server
}

private suspend fun callTool(tools: MarketplaceMcpTools, name: String, arguments: JsonObject): CallToolResult {
    return try {
        val result = tools.result(name, arguments)
        // Only text and images: the tools have no output schema, and clients pass the text to the model, structured
        // content would send the same data a second time.
        val content = result.content.map { content ->
            when (content) {
                is McpContent.Image -> ImageContent(Base64.getEncoder().encodeToString(content.data), content.mimeType)
                is McpContent.Text -> TextContent(content.text)
            }
        }
        CallToolResult(content = listOf(TextContent(Json.encodeToString(result.json))) + content)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        CallToolResult(content = listOf(TextContent(e.message ?: "Invalid arguments")), isError = true)
    } catch (e: Exception) {
        logger.warn("MCP tool $name failed", e)
        CallToolResult(content = listOf(TextContent("Tool $name failed: ${e.message ?: e.javaClass.simpleName}")), isError = true)
    }
}
