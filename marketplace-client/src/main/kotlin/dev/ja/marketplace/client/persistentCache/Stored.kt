/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.persistentCache

/**
 * A value of a persistent cache and the time it was fetched, e.g. to keep it in memory only for the rest of its
 * lifetime after it was read from disk.
 */
class Stored<out T>(val value: T, val fetchedMillis: Long)
