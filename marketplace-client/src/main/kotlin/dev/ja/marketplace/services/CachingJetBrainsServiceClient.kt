/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import com.github.benmanes.caffeine.cache.Caffeine
import dev.ja.marketplace.client.CacheAware
import dev.ja.marketplace.client.CachingStrategy
import dev.ja.marketplace.client.persistentCache.PersistentCacheStore
import dev.ja.marketplace.client.persistentCache.PersistentResponseCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Caches the products of JetBrains and their releases for [maxAge], in memory and in the [store], if it's set.
 * The data is the same for all accounts and is fetched again after [maxAge], also after a restart.
 * The countries are loaded once at the start and aren't cached.
 */
class CachingJetBrainsServiceClient(
    private val delegate: JetBrainsServiceClient,
    store: PersistentCacheStore? = null,
    private val maxAge: Duration = CachingStrategy.Category.Daily.maxAge,
) : JetBrainsServiceClient, CacheAware {
    private val memory = Caffeine.newBuilder()
        .maximumSize(100)
        .expireAfterWrite(maxAge.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .build<String, Any>()

    private val persistent = store?.let {
        PersistentResponseCache(it, CoroutineScope(SupervisorJob() + Dispatchers.IO), maxAge = maxAge)
    }

    override suspend fun countries(): Countries = delegate.countries()

    override suspend fun products(): Products {
        return Products(cached(PRODUCTS_FILE, "products", ListSerializer(ProductWithReleases.serializer())) {
            delegate.products().productsWithReleases
        })
    }

    override suspend fun productReleases(product: JetBrainsProductCode, type: ProductReleaseType): List<ProductRelease> {
        return cached(RELEASES_FILE, "${product.code}.${type.jsonId}", ListSerializer(ProductRelease.serializer())) {
            delegate.productReleases(product, type)
        }
    }

    override fun invalidateCache() {
        memory.invalidateAll()
        persistent?.invalidate()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(fileName: String, key: String, serializer: KSerializer<T>, loader: suspend () -> T): T {
        val memoryKey = "$fileName.$key"
        memory.getIfPresent(memoryKey)?.let { return it as T }

        val value = when (persistent) {
            null -> loader()
            else -> persistent.get(fileName, key, serializer, loader)
        }
        memory.put(memoryKey, value)
        return value
    }

    private companion object {
        const val PRODUCTS_FILE = "jetbrains-products"
        const val RELEASES_FILE = "jetbrains-product-releases"
    }
}
