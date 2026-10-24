/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import java.nio.file.Path

/**
 * The directory of the persistent cache, configured with `--cache-dir`, `MARKETPLACE_CACHE_DIR` or `cacheDir`.
 *
 * @param value The configured path. A leading `~` is the home directory of the user, also if the shell didn't expand it,
 *  e.g. in the configuration file or in the environment variable.
 * @param baseDirectory The directory of relative paths, e.g. the directory of the configuration file.
 *  If it's `null`, relative paths are relative to the working directory.
 * @return The directory, or `null` if the value is empty, which disables the cache.
 */
fun cacheDirectory(value: String, baseDirectory: Path?, homeDirectory: Path = Path.of(System.getProperty("user.home"))): Path? {
    val path = value.trim()
    if (path.isEmpty()) {
        return null
    }

    val expanded = when {
        path == "~" -> homeDirectory
        path.startsWith("~/") || path.startsWith("~\\") -> homeDirectory.resolve(path.substring(2))
        else -> Path.of(path)
    }
    return when {
        expanded.isAbsolute || baseDirectory == null -> expanded
        else -> baseDirectory.resolve(expanded)
    }.normalize()
}
