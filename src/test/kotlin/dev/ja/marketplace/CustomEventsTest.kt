/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.YearMonthDay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class CustomEventsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun events() {
        val events = CustomEventsFile.parse(
            """
            events:
              - id: price-announcement
                plugin: 12345
                date: 2026-10-16
                label: "Announced price change"
              - trial-period:
                plugin: 12345
                date: 2026-03-01
                label: Trial period of 30 days
              - plugin: 678
                date: "2026-05-02"
                label: Blog post
            """.trimIndent()
        )
        assertEquals(
            listOf(
                CustomEvent("trial-period", YearMonthDay(2026, 3, 1), "Trial period of 30 days", 12345),
                CustomEvent("2026-05-02 Blog post", YearMonthDay(2026, 5, 2), "Blog post", 678),
                CustomEvent("price-announcement", YearMonthDay(2026, 10, 16), "Announced price change", 12345),
            ),
            events,
        )
        // an event of a plugin is shown for the plugin and for summaries, which contain the plugin
        assertTrue(events[0].appliesTo(setOf(12345)) && events[0].appliesTo(setOf(678, 12345)))
        assertTrue(!events[0].appliesTo(setOf(678)) && !events[0].appliesTo(emptySet()))
    }

    @Test
    fun invalidEvents() {
        fun error(yaml: String) = assertThrows<IllegalArgumentException> { CustomEventsFile.parse(yaml) }.message!!

        assertEquals("event 1: the date \"2026-16-10\" isn't a valid date like 2026-10-16", error("events:\n  - {plugin: 1, date: 2026-16-10, label: Test}"))
        assertEquals("event 1: the label is missing", error("events:\n  - {plugin: 1, date: 2026-10-16}"))
        assertEquals("event 1: the plugin is missing", error("events:\n  - {date: 2026-10-16, label: Test}"))
        assertEquals("event 1: the plugin \"abc\" isn't the ID of a plugin", error("events:\n  - {plugin: abc, date: 2026-10-16, label: Test}"))
        assertEquals("the file must contain a list \"events\"", error("other: 1"))
        assertEquals("the ID \"a\" is used by more than one event", error("events:\n  - {id: a, plugin: 1, date: 2026-01-01, label: A}\n  - {id: a, plugin: 1, date: 2026-01-02, label: B}"))
        assertTrue(error("events:\n  - {plugin: 1, date: 2026-01-01, label: A, color: red}").startsWith("event 1: unknown properties color"))
        assertEquals(emptyList<CustomEvent>(), CustomEventsFile.parse(""))
    }

    @Test
    fun modifiedFileIsReadAgain() {
        val path = tempDir.resolve("events.yaml")
        Files.writeString(path, "events:\n  - {id: a, plugin: 1, date: 2026-01-01, label: A}")
        val file = CustomEventsFile(path)
        assertEquals(listOf("A"), file.load().map { it.label })

        // an invalid file keeps the previous events
        Files.writeString(path, "events: [")
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().plusSeconds(10)))
        assertEquals(listOf("A"), file.events().map { it.label })

        Files.writeString(path, "events:\n  - {id: b, plugin: 1, date: 2026-01-02, label: B}")
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().plusSeconds(20)))
        assertEquals(listOf("B"), file.events().map { it.label })
    }
}
