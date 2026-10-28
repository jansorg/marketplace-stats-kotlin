/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.cache.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.cache.storage.*
import kotlinx.coroutines.CancellationException
import java.net.http.HttpClient.Version
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Settings of the HTTP clients of the Marketplace and the JetBrains services, see [HttpClientFactory.builder].
 *
 * The builder is immutable, each `with` method returns a new builder. The application configures a builder once,
 * e.g. the log level and the user agent, and passes it to the clients, which add their own settings, e.g. the URL and
 * the API key of the Marketplace, and create their HTTP client with [build]. Settings of a client don't change the
 * builder, which is shared with other clients.
 */
class HttpClientBuilder private constructor(private val settings: Settings) {
    internal constructor() : this(Settings())

    private data class RateLimit(val maxRequests: Int, val period: Duration)

    private data class Settings(
        val baseUrl: String? = null,
        val bearerAuthKey: String? = null,
        val logLevel: ClientLogLevel = ClientLogLevel.None,
        val userAgent: String = HttpClientFactory.DEFAULT_USER_AGENT,
        val httpCacheDirectory: Path? = null,
        val enableRequestRetry: Boolean = true,
        val maxConcurrentRequests: Int = DEFAULT_MAX_CONCURRENT_REQUESTS,
        val maxConcurrentChunkRequests: Int = DEFAULT_MAX_CONCURRENT_CHUNK_REQUESTS,
        val maxConcurrentBackgroundRequests: Int = DEFAULT_MAX_CONCURRENT_BACKGROUND_REQUESTS,
        val connectTimeout: Duration? = DEFAULT_CONNECT_TIMEOUT,
        val requestTimeout: Duration? = DEFAULT_REQUEST_TIMEOUT,
        val rateLimit: RateLimit? = null,
        val engine: HttpClientEngine? = null,
        val configurations: List<HttpClientConfig<*>.() -> Unit> = emptyList(),
    )

    /** The URL of relative requests, e.g. `https://plugins.jetbrains.com`. */
    fun withBaseUrl(url: String?): HttpClientBuilder = HttpClientBuilder(settings.copy(baseUrl = url))

    /** The key, which is sent as bearer token with each request, `null` for requests without authentication. */
    fun withBearerAuth(key: String?): HttpClientBuilder = HttpClientBuilder(settings.copy(bearerAuthKey = key))

    fun withLogLevel(level: ClientLogLevel): HttpClientBuilder = HttpClientBuilder(settings.copy(logLevel = level))

    /** The `User-Agent` of the requests, e.g. `marketplace-stats/<version>`. */
    fun withUserAgent(userAgent: String): HttpClientBuilder = HttpClientBuilder(settings.copy(userAgent = userAgent))

    /**
     * Stores the responses in [directory] and validates them with conditional requests, e.g. `If-Modified-Since`, `null`
     * to disable the cache, the default. Only useful for servers, which support conditional requests, e.g. the services
     * of JetBrains. The Marketplace forbids storing its responses. Responses, which are fresh without a request
     * (`max-age`), would still take the permits and the rate limit of a request.
     */
    fun withHttpCacheDirectory(directory: Path?): HttpClientBuilder = HttpClientBuilder(settings.copy(httpCacheDirectory = directory))

    /** Retries of requests, which failed with server errors, too many requests or exceptions. Enabled by default. */
    fun withRequestRetry(enabled: Boolean): HttpClientBuilder = HttpClientBuilder(settings.copy(enableRequestRetry = enabled))

    /**
     * The maximum number of concurrent requests of a created client, and with it the number of its connections.
     * Each client has its own limit.
     */
    fun withMaxConcurrentRequests(max: Int): HttpClientBuilder {
        require(max > 0) { "The maximum number of concurrent requests must be positive" }
        return HttpClientBuilder(settings.copy(maxConcurrentRequests = max))
    }

    /**
     * The maximum number of concurrent requests of a created client, which are marked with [chunkRequest], e.g. the
     * sales, trials and prices, which are loaded in many small requests. Each client has its own limit.
     */
    fun withMaxConcurrentChunkRequests(max: Int): HttpClientBuilder {
        require(max > 0) { "The maximum number of concurrent chunk requests must be positive" }
        return HttpClientBuilder(settings.copy(maxConcurrentChunkRequests = max))
    }

    /**
     * The maximum number of concurrent chunk requests of a created client, which are made in the background, see
     * [chunkRequest] and [BackgroundRequests]. Each client has its own limit.
     */
    fun withMaxConcurrentBackgroundRequests(max: Int): HttpClientBuilder {
        require(max > 0) { "The maximum number of concurrent background requests must be positive" }
        return HttpClientBuilder(settings.copy(maxConcurrentBackgroundRequests = max))
    }

    /** The time to establish a connection, `null` for no limit. 30 seconds by default. */
    fun withConnectTimeout(timeout: Duration?): HttpClientBuilder = HttpClientBuilder(settings.copy(connectTimeout = timeout.checkTimeout()))

    /**
     * The time of a whole request, from sending it until its response was received, `null` for no limit. 2 minutes by
     * default: the Java engine has no socket timeout, so that a stalled connection must not block a request forever.
     * Downloads of large files may need more time, a request can override it with [io.ktor.client.plugins.timeout].
     * Each retry of a request has its own time.
     */
    fun withRequestTimeout(timeout: Duration?): HttpClientBuilder = HttpClientBuilder(settings.copy(requestTimeout = timeout.checkTimeout()))

    private fun Duration?.checkTimeout(): Duration? {
        require(this == null || isPositive()) { "A timeout must be positive" }
        return this
    }

    /**
     * Limits the requests of a created client to [maxRequests] requests per [period], e.g. 10 requests per second.
     * Any [period] contains at most [maxRequests] requests, requests beyond the limit wait. Each attempt of a request
     * counts, e.g. the retries. Each client has its own limit. There's no limit by default.
     */
    fun withRateLimit(maxRequests: Int, period: Duration = 1.seconds): HttpClientBuilder {
        require(maxRequests > 0) { "The maximum number of requests must be positive" }
        require(period.isPositive()) { "The period of the rate limit must be positive" }
        return HttpClientBuilder(settings.copy(rateLimit = RateLimit(maxRequests, period)))
    }

    /** Removes the rate limit, see [withRateLimit]. */
    fun withoutRateLimit(): HttpClientBuilder = HttpClientBuilder(settings.copy(rateLimit = null))

    /**
     * The engine of the created clients, e.g. a mock engine for tests. The clients don't close it.
     * By default, each client has its own Java engine.
     */
    fun withEngine(engine: HttpClientEngine): HttpClientBuilder = HttpClientBuilder(settings.copy(engine = engine))

    /** Additional configuration of the created clients, it's applied after the configuration of the builder. */
    fun withConfiguration(configure: HttpClientConfig<*>.() -> Unit): HttpClientBuilder {
        return HttpClientBuilder(settings.copy(configurations = settings.configurations + configure))
    }

    /** Creates a new HTTP client with the settings of this builder. */
    fun build(): HttpClient {
        val engine = settings.engine ?: return HttpClient(Java) {
            engine {
                protocolVersion = Version.HTTP_2
            }
            configure()
        }
        return HttpClient(engine) {
            configure()
        }
    }

    private fun HttpClientConfig<*>.configure() {
        install(Logging) {
            level = settings.logLevel.ktorLogLevel
            // the verbose log contains the headers, it must not contain the API key
            sanitizeHeader { it == HttpHeaders.Authorization }
        }

        install(Resources)
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                // to support parsing `Amount` floats as BigDecimal
                isLenient = true
            })
        }

        // the Java engine doesn't decompress responses, the responses of the sales and the searches are large
        install(ContentEncoding) {
            gzip()
            deflate()
        }

        // The cache answers in the send pipeline, within the limits of the Send hooks below. It's only used for servers
        // without max-age, e.g. the JetBrains services: each use of a stored response sends a conditional request,
        // which rightly takes a permit and a slot of the rate limit. Limits behind the cache would be within
        // HttpTimeout, the time waiting for them would count for the timeout.
        settings.httpCacheDirectory?.let { directory ->
            Files.createDirectories(directory)
            install(HttpCache) {
                publicStorage(FileStorage(kotlinx.io.files.Path(directory.toString())))
            }
        }

        if (settings.enableRequestRetry) {
            install(HttpRequestRetry) {
                // server errors and too many requests, e.g. of the many requests of the sales and the prices
                retryIf(4) { request, response ->
                    when {
                        // the server needs too long for the request, it would need too long again
                        response.status == HttpStatusCode.ServiceUnavailable && request.attributes.contains(SlowRequestKey) -> false
                        else -> response.status.value >= 500 || response.status == HttpStatusCode.TooManyRequests
                    }
                }
                // a timeout is retried once, a server, which didn't answer in time, is unlikely to answer the next retries
                retryOnExceptionIf(4) { _, cause ->
                    when {
                        cause.isTimeout() -> retryCount <= 1
                        cause is CancellationException -> false
                        else -> true
                    }
                }
                exponentialDelay()
            }
        }

        // after the retries, so that a request doesn't hold a permit while it waits for a retry
        install(ConcurrentRequestLimit) {
            maxConcurrentRequests = settings.maxConcurrentRequests
            maxConcurrentChunkRequests = settings.maxConcurrentChunkRequests
            maxConcurrentBackgroundRequests = settings.maxConcurrentBackgroundRequests
        }

        // before the rate limit, so that the paused requests are sent at the rate of the limit afterward
        install(ServerBackoffPlugin)

        // the time of a request is reserved after it got its permit of a chunk request, close to the time it's sent
        settings.rateLimit?.let { limit ->
            install(RequestRateLimit) {
                maxRequests = limit.maxRequests
                period = limit.period
            }
        }

        // the plugins of HttpSend wrap the plugins installed after them: the timeout of a request applies to each attempt,
        // a timed out attempt is retried, and the time waiting for a permit, a pause of the server or the rate limit
        // doesn't count
        install(HttpTimeout) {
            connectTimeoutMillis = settings.connectTimeout?.inWholeMilliseconds
            requestTimeoutMillis = settings.requestTimeout?.inWholeMilliseconds
        }

        install(UserAgent) {
            agent = settings.userAgent
        }

        install(DefaultRequest) {
            settings.baseUrl?.let { url(it) }
            settings.bearerAuthKey?.let { bearerAuth(it) }
        }

        expectSuccess = true

        settings.configurations.forEach { it() }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT_REQUESTS: Int = 8
        const val DEFAULT_MAX_CONCURRENT_CHUNK_REQUESTS: Int = 4
        const val DEFAULT_MAX_CONCURRENT_BACKGROUND_REQUESTS: Int = 2
        val DEFAULT_CONNECT_TIMEOUT: Duration = 30.seconds
        val DEFAULT_REQUEST_TIMEOUT: Duration = 2.minutes
    }
}

/** A timeout of the request, of the connection or of the socket, also as the cause of a cancellation. */
private fun Throwable.isTimeout(): Boolean {
    return generateSequence(this, Throwable::cause).any {
        it is HttpRequestTimeoutException || it is ConnectTimeoutException || it is SocketTimeoutException
    }
}
