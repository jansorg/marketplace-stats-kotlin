/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import gg.jte.TemplateEngine
import gg.jte.output.WriterOutput
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.http.content.*
import io.ktor.server.jte.*
import io.ktor.server.response.*
import java.io.BufferedWriter

class StreamingJteConfig {
    lateinit var templateEngine: TemplateEngine
}

/**
 * Responds to [JteContent] like the plugin `Jte` of Ktor, but writes the rendered template directly to the response
 * instead of rendering it into a string first. The browser receives the start of a large page, e.g. a list of 35,000
 * licenses, while the rest is rendered, and the server doesn't keep the whole page in memory. An exception while the
 * template is rendered can't be answered with an error page, the response is incomplete.
 */
val StreamingJte = createApplicationPlugin("StreamingJte", ::StreamingJteConfig) {
    val templateEngine = pluginConfig.templateEngine
    onCallRespond { call ->
        transformBody { content ->
            if (content !is JteContent) {
                return@transformBody content
            }
            content.etag?.let { call.response.header(HttpHeaders.ETag, it) }
            // JTE writes many small strings, the buffer writes them to the response in blocks
            WriterContent({
                val writer = BufferedWriter(this, 64 * 1024)
                templateEngine.render(content.template, content.params, WriterOutput(writer))
                writer.flush()
            }, content.contentType)
        }
    }
}
