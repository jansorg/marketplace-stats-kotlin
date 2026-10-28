/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import com.github.benmanes.caffeine.cache.Caffeine
import dev.hsbrysk.caffeine.buildCoroutine
import dev.ja.marketplace.client.CacheAware
import dev.ja.marketplace.client.CachingStrategy
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Caches the products of JetBrains and their releases in memory for [maxAge].
 * The data is the same for all accounts. The [delegate] can store the responses on disk, e.g. with an HTTP cache
 * ([dev.ja.marketplace.client.HttpClientBuilder.withHttpCacheDirectory]), the services answer unchanged data with 304.
 * The countries are loaded once at the start and aren't cached.
 */
class CachingJetBrainsServiceClient(
    private val delegate: JetBrainsServiceClient,
    maxAge: Duration = CachingStrategy.Category.Daily.maxAge,
) : JetBrainsServiceClient, CacheAware {
    // concurrent requests of the same data wait for the same load
    private val memory = Caffeine.newBuilder()
        .maximumSize(100)
        .expireAfterWrite(maxAge.toJavaDuration())
        .buildCoroutine<String, Any>()

    /** Closes the [delegate]. */
    override fun close() {
        delegate.close()
    }

    override suspend fun countries(): Countries = delegate.countries()

    override suspend fun products(): Products {
        return Products(cached("products") { delegate.products().productsWithReleases })
    }

    override suspend fun productReleases(product: JetBrainsProductCode, type: ProductReleaseType): List<ProductRelease> {
        return cached("releases.${product.code}.${type.jsonId}") { delegate.productReleases(product, type) }
    }

    override fun invalidateCache() {
        memory.synchronous().invalidateAll()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, loader: suspend () -> T): T {
        return memory.get(key) { loader() } as T
    }
}
