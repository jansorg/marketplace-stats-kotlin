/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import dev.ja.marketplace.client.model.MarketplacePluginSearchRequest
import dev.ja.marketplace.client.model.PluginSearchMode
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Marketplace only returns the first 10,000 results of a plugin search.
 * The mock behaves like the API: pages of at most 24 results, `offset + max` must not exceed the window or the total,
 * the total is capped at the window, `tags` are combined with OR and `excludeTags` exclude plugins with any of the tags.
 */
class PluginSearchWindowTest {
    private val window = Marketplace.MAX_SEARCH_RESULT_WINDOW
    private val tags = listOf("Theme", "Code Tools", "Editor", "AI", "Unused")

    private data class Plugin(val id: Int, val tags: List<String>)

    // more plugins than the window, some without tags, some with several tags and some with a tag of another list
    private val plugins = (1..window + 5_000).map { id ->
        Plugin(
            id, when (id % 7) {
                0 -> emptyList()
                1 -> listOf("Theme")
                2 -> listOf("Code Tools", "Editor")
                3 -> listOf("Editor", "AI")
                4 -> listOf("AI")
                5 -> listOf("Code Tools")
                else -> listOf("Other")
            }
        )
    }

    private val searchRequests = AtomicInteger()
    private val tagRequests = AtomicInteger()

    private fun createClient(): KtorMarketplaceClient {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/tags" -> {
                    tagRequests.incrementAndGet()
                    val json = buildJsonArray {
                        tags.forEachIndexed { index, tag ->
                            addJsonObject {
                                put("id", index)
                                put("name", tag)
                                put("privileged", false)
                                put("searchable", true)
                                put("link", "/tag/$index")
                                putJsonArray("families") { add("intellij") }
                            }
                        }
                    }
                    respond(json.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }

                "/api/searchPlugins" -> {
                    searchRequests.incrementAndGet()
                    search(request.url.parameters)
                }

                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        return KtorMarketplaceClient(httpClientBuilder = mockHttpClientBuilder(engine))
    }

    private fun matches(requiredTags: Set<String>, excludedTags: Set<String>): List<Plugin> {
        return plugins.filter { plugin ->
            val pluginTags = plugin.tags.map { it.lowercase() }
            (requiredTags.isEmpty() || pluginTags.any { it in requiredTags }) && pluginTags.none { it in excludedTags }
        }
    }

    private fun pages(results: Int): Int = maxOf(1, (results + Marketplace.MAX_SEARCH_RESULT_SIZE - 1) / Marketplace.MAX_SEARCH_RESULT_SIZE)

    private fun MockRequestHandleScope.search(parameters: Parameters): HttpResponseData {
        val max = parameters["max"]!!.toInt()
        val offset = parameters["offset"]!!.toInt()
        val requiredTags = parameters.getAll("tags").orEmpty().map { it.lowercase() }.toSet()
        val excludedTags = parameters.getAll("excludeTags").orEmpty().map { it.lowercase() }.toSet()

        val matches = matches(requiredTags, excludedTags)
        if (max !in 1..Marketplace.MAX_SEARCH_RESULT_SIZE || offset + max > window) {
            return respondError(HttpStatusCode.BadRequest, """{"statusCode":400,"message":"Invalid max value: $max."}""")
        }

        val json = buildJsonObject {
            put("total", minOf(matches.size, window))
            putJsonArray("plugins") {
                matches.drop(offset).take(max).forEach { plugin ->
                    addJsonObject {
                        put("id", plugin.id)
                        put("name", "Plugin ${plugin.id}")
                        put("xmlId", "plugin.${plugin.id}")
                        put("downloads", 0)
                        put("pricingModel", "FREE")
                        put("hasSource", false)
                        putJsonArray("tags") { plugin.tags.forEach { add(it) } }
                    }
                }
            }
        }
        return respond(json.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    // the default request excludes themes
    private val expectedIds = plugins.filter { "Theme" !in it.tags }.map { it.id }

    private val anySize = MarketplacePluginSearchRequest(searchMode = PluginSearchMode.AnySizeWorkaround)

    @Test
    suspend fun marketplaceModeIsLimitedToTheWindow() {
        val client = createClient()
        val result = client.marketplacePluginsSearch(MarketplacePluginSearchRequest()).map { it.id }

        assertEquals(expectedIds.take(window), result)
        assertEquals(0, tagRequests.get())
        assertEquals(pages(window), searchRequests.get())

        val acrossWindow = client.marketplacePluginsSearch(MarketplacePluginSearchRequest(offset = window - 10, maxResults = 30))
        assertEquals(expectedIds.subList(window - 10, window), acrossWindow.map { it.id })

        val afterWindow = client.marketplacePluginsSearch(MarketplacePluginSearchRequest(offset = window + 100, maxResults = 50))
        assertTrue(afterWindow.isEmpty())
    }

    @Test
    suspend fun anySizeModeReturnsAllResults() {
        val result = createClient().marketplacePluginsSearch(anySize).map { it.id }

        assertEquals(result.size, result.toSet().size, "no duplicates")
        assertEquals(expectedIds.toSet(), result.toSet())
        assertEquals(1, tagRequests.get())

        // a request to detect the window and the pages of the slices, but not the pages of the window
        val sliceTags = tags.filter { it != "Theme" }.map { it.lowercase() }
        val slicePages = sliceTags.indices.sumOf { index ->
            pages(matches(setOf(sliceTags[index]), setOf("theme") + sliceTags.take(index)).size)
        } + pages(matches(emptySet(), setOf("theme") + sliceTags).size)
        assertEquals(1 + slicePages, searchRequests.get())
        assertTrue(searchRequests.get() < pages(window) + slicePages)
    }

    @Test
    suspend fun anySizeModeWithOffsetAndMaxResults() {
        val client = createClient()
        val all = client.marketplacePluginsSearch(anySize).map { it.id }

        val acrossWindow = client.marketplacePluginsSearch(anySize.copy(offset = window - 10, maxResults = 30))
        assertEquals(all.subList(window - 10, window + 20), acrossWindow.map { it.id })

        val afterWindow = client.marketplacePluginsSearch(anySize.copy(offset = window + 100, maxResults = 50))
        assertEquals(all.subList(window + 100, window + 150), afterWindow.map { it.id })

        val first = client.marketplacePluginsSearch(anySize.copy(maxResults = 100))
        assertEquals(all.take(100), first.map { it.id })
    }

    @Test
    suspend fun anySizeModeWithRequiredTags() {
        val request = anySize.copy(requiredTags = listOf("Code Tools", "Editor", "AI", "Other"))
        val result = createClient().marketplacePluginsSearch(request).map { it.id }

        val expected = plugins.filter { plugin -> plugin.tags.isNotEmpty() && "Theme" !in plugin.tags }.map { it.id }
        assertTrue(expected.size > window)
        assertEquals(result.size, result.toSet().size, "no duplicates")
        assertEquals(expected.toSet(), result.toSet())
        // only the required tags are searched, the list of tags isn't needed
        assertEquals(0, tagRequests.get())
    }

    @Test
    suspend fun anySizeModeWithSmallSearchDoesNotUseSlices() {
        val result = createClient().marketplacePluginsSearch(anySize.copy(requiredTags = listOf("AI")))

        // in the requested order
        assertEquals(plugins.filter { "AI" in it.tags }.map { it.id }, result.map { it.id })
        assertEquals(0, tagRequests.get())
        assertEquals(1 + pages(result.size), searchRequests.get())
    }
}
