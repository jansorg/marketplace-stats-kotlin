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
    private val directories = mapOf("/js/" to "js/", "/styles/" to "styles/", "/favicon/" to "favicon/", "/fonts/" to "fonts/")

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

    /** The unicode ranges of the subsets of the font, like the CSS of the npm package @fontsource/lato. */
    private val fontSubsets = listOf(
        "latin-ext" to "U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,U+0308,U+0329,U+1D00-1DBF," +
                "U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,U+A720-A7FF",
        "latin" to "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F," +
                "U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD",
    )

    /** The styles of the font, which the pages use: regular, bold and italic text. */
    private val fontStyles = listOf(Triple("normal", 400, "400-normal"), Triple("normal", 700, "700-normal"), Triple("italic", 400, "400-italic"))

    /**
     * @return The CSS rules of the font Lato of the pages, with the hashes of the font files in their addresses, see
     *  `fonts.kte`. The browser only loads the files of the shown styles and characters.
     */
    fun fontFaceRules(): String {
        return fontStyles.flatMap { (style, weight, file) ->
            fontSubsets.map { (subset, range) ->
                "@font-face{font-family:'Lato';font-style:$style;font-weight:$weight;font-display:swap;" +
                        "src:url(${url("/fonts/lato-$subset-$file.woff2")}) format('woff2');unicode-range:$range}"
            }
        }.joinToString("\n")
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
