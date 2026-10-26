/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import io.ktor.http.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.net.URL
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.getLastModifiedTime

/**
 * The addresses of the static files of the pages, e.g. scripts and stylesheets, with the hash of their content, e.g.
 * `/js/charts.min.js?v=3f2a9c1b0d4e`. A new version of a file has a new address, so the browsers can cache the files
 * for a long time, see [VERSION_PARAMETER] and the static routes of [MarketplaceStatsServer].
 */
object StaticAssets {
    /** The query parameter with the hash of the content of a file. */
    const val VERSION_PARAMETER = "v"

    /** The served directories of the resources by the path of their URL. */
    private val directories = mapOf("/js/" to "js/", "/styles/" to "styles/", "/favicon/" to "favicon/")

    /** The hash of the content of each path, and the modification time of a file, e.g. of the build of the IDE. */
    private class Hash(val value: String?, val lastModified: Long?)

    private val hashes = ConcurrentHashMap<String, Hash>()

    /**
     * @param path The path of a static file, e.g. `/js/charts.min.js`.
     * @return The path with the hash of the content of the file, or the path without a hash, if it isn't a resource.
     */
    fun url(path: String): String {
        val resource = resourceOf(path)
        // resources in a JAR don't change, files, e.g. of a server of the IDE, are hashed again after a build
        val lastModified = resource?.let(::fileLastModified)
        val hash = hashes.compute(path) { _, cached ->
            cached?.takeIf { it.lastModified == lastModified } ?: Hash(resource?.let(::contentHash), lastModified)
        }!!.value
        return if (hash == null) path else "$path?$VERSION_PARAMETER=$hash"
    }

    /** @return The URL of the resource of the path of a static file, null if it isn't a resource. */
    private fun resourceOf(path: String): URL? {
        val (prefix, directory) = directories.entries.firstOrNull { path.startsWith(it.key) } ?: return null
        return StaticAssets::class.java.classLoader.getResource(directory + path.removePrefix(prefix))
    }

    /** @return The modification time of a resource, which is a file, null for resources in a JAR. */
    private fun fileLastModified(resource: URL): Long? {
        return if (resource.protocol == "file") Path.of(resource.toURI()).getLastModifiedTime().toMillis() else null
    }

    /** @return The first 12 hex digits of the SHA-256 of the content of the resource. */
    private fun contentHash(resource: URL): String {
        val bytes = resource.openStream().use { it.readBytes() }
        return MessageDigest.getInstance("SHA-256").digest(bytes).toHexString().take(12)
    }
}

/**
 * Serves the static files of a directory of the resources with the hash of their content as ETag, so that a browser,
 * which already has a file, gets 304 (with the plugin ConditionalHeaders). A request with the hash in its address, see
 * [StaticAssets.url], may be cached for a year, the address of another version is different. A request without it
 * is validated with the ETag each time.
 */
fun Route.staticAssets(remotePath: String, basePackage: String) {
    staticResources(remotePath, basePackage) {
        etag(ETagProvider.StrongSha256)
        modify { _, call ->
            val cacheControl = when (call.request.queryParameters[StaticAssets.VERSION_PARAMETER]) {
                null -> "no-cache"
                else -> "public, max-age=31536000, immutable"
            }
            call.response.header(HttpHeaders.CacheControl, cacheControl)
        }
    }
}
