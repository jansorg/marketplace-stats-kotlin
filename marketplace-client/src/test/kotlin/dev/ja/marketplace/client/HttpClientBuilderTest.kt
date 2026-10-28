/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpServer
import dev.ja.marketplace.services.KtorJetBrainsServiceClient
import io.ktor.client.call.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream

/**
 * Requests of the clients of [HttpClientBuilder] with the Java engine to a local server.
 */
class HttpClientBuilderTest {
    @Serializable
    private data class Item(val name: String)

    private lateinit var server: HttpServer
    private val requestHeaders = CopyOnWriteArrayList<Headers>()
    private val logMessages = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/item") { exchange ->
            requestHeaders += exchange.requestHeaders
            val json = """{"name": "gzip"}""".toByteArray()
            val acceptsGzip = exchange.requestHeaders["Accept-Encoding"].orEmpty().any { "gzip" in it }
            val body = when {
                acceptsGzip -> ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(json) } }.toByteArray()
                else -> json
            }
            exchange.responseHeaders.add("Content-Type", "application/json")
            if (acceptsGzip) {
                exchange.responseHeaders.add("Content-Encoding", "gzip")
            }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        // like the services of JetBrains: a 304 only for the unchanged value of Last-Modified
        server.createContext("/products") { exchange ->
            requestHeaders += exchange.requestHeaders
            exchange.responseHeaders.add("Last-Modified", LAST_MODIFIED)
            if (exchange.requestHeaders.getFirst("If-Modified-Since") == LAST_MODIFIED) {
                exchange.sendResponseHeaders(304, -1)
                exchange.close()
                return@createContext
            }
            val body = """{"name": "products"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/services/countries.json") { exchange ->
            requestHeaders += exchange.requestHeaders
            val body = "[]".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private val serverUrl: String
        get() = "http://${server.address.hostString}:${server.address.port}"

    /** The builder of the application with the URL of the local server, the log messages are captured. */
    private fun builder(): HttpClientBuilder {
        return HttpClientFactory.builder()
            .withBaseUrl(serverUrl)
            .withConfiguration {
                // adds to the configuration of the builder
                install(Logging) {
                    logger = object : Logger {
                        override fun log(message: String) {
                            logMessages += message
                        }
                    }
                }
            }
    }

    @Test
    suspend fun decompressesGzipResponses() {
        builder().build().use { client ->
            assertEquals(Item("gzip"), client.get("/item").body<Item>())
        }

        val acceptEncoding = requestHeaders.single()["Accept-Encoding"].orEmpty().joinToString(",")
        assertTrue("gzip" in acceptEncoding, acceptEncoding)
        assertTrue("deflate" in acceptEncoding, acceptEncoding)
    }

    @Test
    suspend fun requestHeaders() {
        builder().withUserAgent("marketplace-stats/1.2.3").withBearerAuth(API_KEY).build().use { client ->
            client.get("/item").body<Item>()
        }

        val headers = requestHeaders.single()
        assertEquals(listOf("marketplace-stats/1.2.3"), headers["User-Agent"])
        assertEquals(listOf("Bearer $API_KEY"), headers["Authorization"])
        // GET requests have no body
        assertNull(headers["Content-Type"])
    }

    @Test
    suspend fun defaultUserAgent() {
        builder().build().use { client ->
            client.get("/item").body<Item>()
        }

        assertEquals(listOf(HttpClientFactory.DEFAULT_USER_AGENT), requestHeaders.single()["User-Agent"])
    }

    @Test
    suspend fun verboseLogDoesNotContainApiKey() {
        builder().withLogLevel(ClientLogLevel.Verbose).withBearerAuth(API_KEY).build().use { client ->
            client.get("/item").body<Item>()
        }

        val log = logMessages.joinToString("\n")
        assertTrue(log.contains("${HttpHeaders.Authorization}: ***"), log)
        assertFalse(log.contains(API_KEY), log)
    }

    @Test
    suspend fun derivedBuildersDontChangeTheBuilder() {
        val builder = builder().withUserAgent("marketplace-stats/1.2.3")
        builder.withBearerAuth(API_KEY).withUserAgent("other").build().use { it.get("/item").body<Item>() }
        builder.build().use { it.get("/item").body<Item>() }

        val headers = requestHeaders[1]
        assertNull(headers["Authorization"])
        assertEquals(listOf("marketplace-stats/1.2.3"), headers["User-Agent"])
    }

    @Test
    suspend fun jetBrainsServicesGetNoApiKey() {
        // e.g. the builder of the application, which has the key of the Marketplace
        val builder = builder().withBearerAuth(API_KEY).withUserAgent("marketplace-stats/1.2.3")
        KtorJetBrainsServiceClient(builder, accountServicesUrl = serverUrl).use { client ->
            client.countries()
        }

        val headers = requestHeaders.single()
        assertNull(headers["Authorization"])
        assertEquals(listOf("marketplace-stats/1.2.3"), headers["User-Agent"])
    }

    @Test
    suspend fun httpCacheValidatesStoredResponses(@TempDir cacheDir: Path) {
        // a new client, e.g. after a restart, uses the stored response
        repeat(2) {
            builder().withHttpCacheDirectory(cacheDir).build().use { client ->
                assertEquals(Item("products"), client.get("/products").body<Item>())
            }
        }

        assertEquals(2, requestHeaders.size)
        assertNull(requestHeaders[0]["If-Modified-Since"])
        assertEquals(listOf(LAST_MODIFIED), requestHeaders[1]["If-Modified-Since"])
    }

    @Test
    suspend fun withoutHttpCache() {
        repeat(2) {
            builder().build().use { client -> client.get("/products").body<Item>() }
        }
        assertNull(requestHeaders[1]["If-Modified-Since"])
    }

    private companion object {
        const val API_KEY = "secret-api-key"
        const val LAST_MODIFIED = "Mon, 26 Oct 2026 11:02:17 GMT"
    }
}
