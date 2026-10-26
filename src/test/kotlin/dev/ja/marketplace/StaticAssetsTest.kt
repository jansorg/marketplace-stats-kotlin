/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.conditionalheaders.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class StaticAssetsTest {
    private fun hashOf(resource: String): String {
        val bytes = javaClass.classLoader.getResourceAsStream(resource)!!.use { it.readBytes() }
        return MessageDigest.getInstance("SHA-256").digest(bytes).toHexString().take(12)
    }

    @Test
    fun addressesContainTheHashOfTheContent() {
        assertEquals("/styles/main.min.css?v=${hashOf("styles/main.min.css")}", StaticAssets.url("/styles/main.min.css"))
        assertEquals("/js/charts.min.js?v=${hashOf("js/charts.min.js")}", StaticAssets.url("/js/charts.min.js"))
        // unknown files and other paths have no hash
        assertEquals("/js/unknown.js", StaticAssets.url("/js/unknown.js"))
        assertEquals("/favicon.ico", StaticAssets.url("/favicon.ico"))
    }

    @Test
    fun fontFilesOfAllStylesAndSubsetsAreServedWithTheirHash() {
        val rules = StaticAssets.fontFaceRules().lines()
        assertEquals(6, rules.size)
        for (file in listOf("latin-400-normal", "latin-ext-400-normal", "latin-700-normal", "latin-ext-700-normal", "latin-400-italic", "latin-ext-400-italic")) {
            val resource = "fonts/lato-$file.woff2"
            assertEquals(1, rules.count { "url(/$resource?v=${hashOf(resource)})" in it }, file)
        }
    }

    @Test
    fun versionedFilesAreCachedAndOtherFilesAreValidatedWithTheirETag() = testApplication {
        application {
            install(ConditionalHeaders)
            routing {
                staticAssets("/styles", "styles")
            }
        }

        val versioned = client.get(StaticAssets.url("/styles/main.min.css"))
        assertEquals(HttpStatusCode.OK, versioned.status)
        assertEquals("public, max-age=31536000, immutable", versioned.headers[HttpHeaders.CacheControl])
        val etag = versioned.headers[HttpHeaders.ETag]
        assertNotNull(etag)

        val unversioned = client.get("/styles/main.min.css")
        assertEquals(HttpStatusCode.OK, unversioned.status)
        assertEquals("no-cache", unversioned.headers[HttpHeaders.CacheControl])
        assertEquals(etag, unversioned.headers[HttpHeaders.ETag])

        // the browser has the file, its content is the same
        val unchanged = client.get("/styles/main.min.css") { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, unchanged.status)
    }
}
