/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.PluginId
import dev.ja.marketplace.client.YearMonthDay
import org.slf4j.LoggerFactory
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * An event, which the charts show as a vertical line, e.g. a blog post or a change of the trial period.
 * These events aren't part of the data of the Marketplace, they're configured in a file, see [CustomEventsFile].
 *
 * @param id The ID of the event, the charts store the IDs of the shown events.
 * @param plugin The plugin of the event.
 */
data class CustomEvent(val id: String, val date: YearMonthDay, val label: String, val plugin: PluginId) {
    /**
     * @param plugins The plugins of a page, e.g. all paid plugins for the summary of all plugins.
     */
    fun appliesTo(plugins: Collection<PluginId>): Boolean = plugin in plugins
}

/**
 * The custom events of a YAML file, e.g.
 * ```yaml
 * events:
 *   - id: price-announcement
 *     plugin: 12345
 *     date: 2026-10-16
 *     label: "Announced price change"
 * ```
 * `plugin` (the ID of the plugin), `date` (`YYYY-MM-DD`) and `label` are required. `id` is optional, it defaults to the
 * date and the label. Instead of `id`, the ID can be a key without value, e.g. `- price-announcement:`.
 * The file is read again when it was modified, so that events can be changed without a restart.
 */
class CustomEventsFile(val path: Path) {
    @Volatile
    private var cached: Pair<FileTime, List<CustomEvent>>? = null

    /**
     * Reads the file, e.g. at the start to report errors.
     * @throws IllegalArgumentException If the file can't be read or is invalid.
     */
    fun load(): List<CustomEvent> {
        val modified = Files.getLastModifiedTime(path)
        val events = try {
            parse(Files.readString(path))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid events file $path: ${e.message}", e)
        }
        cached = modified to events
        return events
    }

    /**
     * @return The events of the file. A modified file is read again, if it's invalid, the error is logged and the
     *  previous events are returned.
     */
    fun events(): List<CustomEvent> {
        val previous = cached
        return try {
            if (previous != null && Files.getLastModifiedTime(path) == previous.first) previous.second else load()
        } catch (e: Exception) {
            logger.warn("Unable to read the events of $path, the previous events are used: ${e.message}")
            previous?.second ?: emptyList()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CustomEventsFile::class.java)

        private val KNOWN_KEYS = setOf("id", "plugin", "date", "label")

        /**
         * @throws IllegalArgumentException If the YAML isn't a valid list of events.
         */
        fun parse(yaml: String): List<CustomEvent> {
            val document = try {
                Load(LoadSettings.builder().build()).loadFromString(yaml)
            } catch (e: Exception) {
                throw IllegalArgumentException("not valid YAML: ${e.message}", e)
            }
            if (document == null) {
                return emptyList()
            }
            val events = (document as? Map<*, *>)?.get("events")
                ?: throw IllegalArgumentException("the file must contain a list \"events\"")
            if (events !is List<*>) {
                throw IllegalArgumentException("\"events\" must be a list")
            }

            val parsed = events.mapIndexed { index, item -> parseEvent(index + 1, item) }
            parsed.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
                throw IllegalArgumentException("the ID \"$it\" is used by more than one event")
            }
            return parsed.sortedBy { it.date }
        }

        private fun parseEvent(number: Int, item: Any?): CustomEvent {
            val event = item as? Map<*, *> ?: throw IllegalArgumentException("event $number must have the properties plugin, date and label")
            fun invalid(message: String): Nothing = throw IllegalArgumentException("event $number: $message")

            // a key without value is the ID, e.g. "- price-announcement:"
            val idKeys = event.filter { (key, value) -> key !in KNOWN_KEYS && value == null }.keys
            val unknownKeys = event.keys.filter { it !in KNOWN_KEYS && it !in idKeys }
            if (unknownKeys.isNotEmpty() || idKeys.size > 1) {
                invalid("unknown properties ${(unknownKeys + idKeys.drop(1)).joinToString()}, supported are id, plugin, date and label")
            }

            val dateText = event["date"]?.toString() ?: invalid("the date is missing")
            val date = try {
                LocalDate.parse(dateText).let { YearMonthDay(it.year, it.monthValue, it.dayOfMonth) }
            } catch (e: DateTimeParseException) {
                invalid("the date \"$dateText\" isn't a valid date like 2026-10-16")
            }
            val label = event["label"]?.toString()?.trim()?.takeIf(String::isNotEmpty) ?: invalid("the label is missing")
            val plugin = when (val value = event["plugin"]) {
                null -> invalid("the plugin is missing")
                is Number -> value.toInt()
                else -> value.toString().toIntOrNull() ?: invalid("the plugin \"$value\" isn't the ID of a plugin")
            }
            val id = event["id"]?.toString() ?: idKeys.firstOrNull()?.toString() ?: "${date.asIsoString} $label"
            return CustomEvent(id, date, label, plugin)
        }
    }
}
