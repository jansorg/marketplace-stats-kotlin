/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

@file:OptIn(ExperimentalSerializationApi::class)

package dev.ja.marketplace.client.persistentCache

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.protobuf.ProtoBuf
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * Stores serialized data in files of a directory, used to persist expensive data between server restarts.
 *
 * A file contains a header and the gzip-compressed ProtoBuf data. The header has a magic number,
 * the version of the file format and the version of the stored data, which is defined by the caller.
 * Files with a different version, and files which can't be read, are deleted and treated as missing.
 * Files are written to a temporary file first and then moved to the target, a crash never leaves a partially written file.
 *
 * Reading and writing never throw because of I/O or format errors, the data is reloaded from the API in that case.
 */
class PersistentCacheStore(val directory: Path) {
    /**
     * @return A store for a subdirectory of this store's directory.
     */
    fun subStore(name: String): PersistentCacheStore {
        require(isValidName(name)) { "Invalid name: $name" }
        return PersistentCacheStore(directory.resolve(name))
    }

    /**
     * @return A store for the data of the account, which is identified by the API host and key.
     *  Only a hash of the key is used, the key itself is not stored.
     */
    fun accountStore(apiHost: String, apiKey: String): PersistentCacheStore {
        return subStore("accounts").subStore(accountDirectoryName(apiHost, apiKey))
    }

    /**
     * @return The stored value, or `null` if no file exists, or if it's unreadable or has a different version.
     */
    fun <T> read(name: String, dataVersion: Int, serializer: KSerializer<T>): T? {
        val file = file(name)
        if (!file.exists()) {
            return null
        }

        try {
            DataInputStream(Files.newInputStream(file).buffered(BUFFER_SIZE)).use { input ->
                val magic = input.readInt()
                val fileFormatVersion = input.readInt()
                val fileDataVersion = input.readInt()
                if (magic != MAGIC || fileFormatVersion != FORMAT_VERSION || fileDataVersion != dataVersion) {
                    logger.info("Dropping cache file $file, it has an unsupported format or version")
                    file.deleteIfExists()
                    return null
                }

                // reading all bytes verifies the checksum and size of the gzip data
                val data = GZIPInputStream(input, BUFFER_SIZE).readAllBytes()
                return ProtoBuf.decodeFromByteArray(serializer, data)
            }
        } catch (e: Exception) {
            logger.warn("Dropping unreadable cache file $file: $e")
            try {
                file.deleteIfExists()
            } catch (e: IOException) {
                logger.warn("Unable to delete cache file $file: $e")
            }
            return null
        }
    }

    /**
     * Atomically replaces the file with the given value.
     * @return `true` if the value was stored
     */
    fun <T> write(name: String, dataVersion: Int, serializer: KSerializer<T>, value: T): Boolean {
        val file = file(name)
        var tempFile: Path? = null
        try {
            val data = ProtoBuf.encodeToByteArray(serializer, value)

            Files.createDirectories(file.parent)
            tempFile = Files.createTempFile(file.parent, file.name, TEMP_FILE_SUFFIX)
            FileOutputStream(tempFile.toFile()).use { fileOutput ->
                val output = DataOutputStream(BufferedOutputStream(fileOutput, BUFFER_SIZE))
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeInt(dataVersion)
                // fast compression, the size is close to the default level, but compression takes less than half of the time
                val gzipOutput = object : GZIPOutputStream(output, BUFFER_SIZE) {
                    init {
                        def.setLevel(Deflater.BEST_SPEED)
                    }
                }
                gzipOutput.write(data)
                gzipOutput.finish()
                output.flush()
                // the data must be on disk before the file is moved to the target
                fileOutput.fd.sync()
            }

            try {
                Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING)
            }
            return true
        } catch (e: Exception) {
            logger.warn("Unable to write cache file $file: $e")
            return false
        } finally {
            try {
                tempFile?.deleteIfExists()
            } catch (_: IOException) {
                // ignored
            }
        }
    }

    fun delete(name: String) {
        try {
            file(name).deleteIfExists()
        } catch (e: IOException) {
            logger.warn("Unable to delete cache file ${file(name)}: $e")
        }
    }

    /** @return If data with the name is stored. */
    fun exists(name: String): Boolean {
        return Files.exists(file(name))
    }

    internal fun file(name: String): Path {
        require(isValidName(name)) { "Invalid name: $name" }
        return directory.resolve("$name$FILE_SUFFIX")
    }

    override fun toString(): String = "PersistentCacheStore($directory)"

    companion object {
        private val logger = LoggerFactory.getLogger(PersistentCacheStore::class.java)

        // "MSPC", Marketplace Stats persistent cache
        private const val MAGIC = 0x4d535043

        // version of the file layout, i.e. the header, compression and serialization format
        internal const val FORMAT_VERSION = 1

        private const val FILE_SUFFIX = ".bin"
        private const val TEMP_FILE_SUFFIX = ".tmp"
        private const val BUFFER_SIZE = 64 * 1024

        private val validName = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]*")

        private fun isValidName(name: String): Boolean = validName.matches(name)

        internal fun accountDirectoryName(apiHost: String, apiKey: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$apiHost\n$apiKey".toByteArray(Charsets.UTF_8))
            return "account-" + digest.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}
