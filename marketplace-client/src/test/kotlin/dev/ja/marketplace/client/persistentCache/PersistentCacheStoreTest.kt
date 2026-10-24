/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.persistentCache

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

@OptIn(ExperimentalSerializationApi::class)
class PersistentCacheStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Serializable
    data class Value(val name: String, val numbers: List<Int> = emptyList())

    private val store by lazy { PersistentCacheStore(tempDir.resolve("cache")) }

    @Test
    fun roundTrip() {
        val value = Value("name", listOf(1, 2, 3))
        assertTrue(store.write("value", 1, Value.serializer(), value))
        assertEquals(value, store.read("value", 1, Value.serializer()))
    }

    @Test
    fun missingFile() {
        assertNull(store.read("value", 1, Value.serializer()))
    }

    @Test
    fun dataVersionMismatchDropsFile() {
        store.write("value", 1, Value.serializer(), Value("name"))

        assertNull(store.read("value", 2, Value.serializer()))
        assertFalse(store.file("value").exists(), "file with a different version must be deleted")
        assertNull(store.read("value", 1, Value.serializer()))
    }

    @Test
    fun formatVersionMismatchDropsFile() {
        Files.createDirectories(store.directory)
        DataOutputStream(Files.newOutputStream(store.file("value"))).use {
            it.writeInt(0x4d535043)
            it.writeInt(PersistentCacheStore.FORMAT_VERSION + 1)
            it.writeInt(1)
        }

        assertNull(store.read("value", 1, Value.serializer()))
        assertFalse(store.file("value").exists())
    }

    @Test
    fun unknownFileDropped() {
        Files.createDirectories(store.directory)
        store.file("value").writeBytes("{\"name\": \"json\"}".toByteArray())

        assertNull(store.read("value", 1, Value.serializer()))
        assertFalse(store.file("value").exists())
    }

    @Test
    fun truncatedFileDropped() {
        store.write("value", 1, Value.serializer(), Value("name", (1..1000).toList()))
        val bytes = store.file("value").readBytes()
        store.file("value").writeBytes(bytes.copyOf(bytes.size - 10))

        assertNull(store.read("value", 1, Value.serializer()))
        assertFalse(store.file("value").exists())
    }

    @Test
    fun modifiedDataDropped() {
        store.write("value", 1, Value.serializer(), Value("name", (1..1000).toList()))
        val bytes = store.file("value").readBytes()
        // the gzip checksum detects the modification
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        store.file("value").writeBytes(bytes)

        assertNull(store.read("value", 1, Value.serializer()))
        assertFalse(store.file("value").exists())
    }

    // a different type without the required fields of Value
    @Serializable
    class Other(@ProtoNumber(5) val count: Long)

    @Test
    fun incompatibleDataDropped() {
        store.write("value", 1, Other.serializer(), Other(42))
        assertNull(store.read("value", 1, Value.serializer()))
        assertFalse(store.file("value").exists())
    }

    @Test
    fun writeReplacesFileWithoutLeftovers() {
        store.write("value", 1, Value.serializer(), Value("first"))
        store.write("value", 1, Value.serializer(), Value("second"))

        assertEquals(Value("second"), store.read("value", 1, Value.serializer()))
        assertEquals(listOf(store.file("value")), store.directory.listDirectoryEntries())
    }

    @Test
    fun failedWriteKeepsPreviousFile() {
        store.write("value", 1, Value.serializer(), Value("first"))

        val failingSerializer = object : KSerializer<Value> {
            override val descriptor: SerialDescriptor = Value.serializer().descriptor
            override fun deserialize(decoder: Decoder): Value = throw UnsupportedOperationException()
            override fun serialize(encoder: Encoder, value: Value) = throw IllegalStateException("failure")
        }
        assertFalse(store.write("value", 1, failingSerializer, Value("second")))

        assertEquals(Value("first"), store.read("value", 1, Value.serializer()))
        assertEquals(listOf(store.file("value")), store.directory.listDirectoryEntries())
    }

    @Test
    fun leftoverTemporaryFileIgnored() {
        store.write("value", 1, Value.serializer(), Value("first"))
        // a temporary file of a write, which was interrupted by a crash
        store.directory.resolve("value.bin12345.tmp").writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(Value("first"), store.read("value", 1, Value.serializer()))
    }

    @Test
    fun unwritableDirectory() {
        val file = tempDir.resolve("file")
        file.writeBytes(byteArrayOf(1))
        // a file is in the way of the directory
        val store = PersistentCacheStore(file.resolve("cache"))

        assertFalse(store.write("value", 1, String.serializer(), "value"))
        assertNull(store.read("value", 1, String.serializer()))
    }

    @Test
    fun accountStores() {
        val root = PersistentCacheStore(tempDir)
        val first = root.accountStore("plugins.jetbrains.com", "secret-key-1")
        val second = root.accountStore("plugins.jetbrains.com", "secret-key-2")
        val otherHost = root.accountStore("example.com", "secret-key-1")

        assertEquals(first.directory, root.accountStore("plugins.jetbrains.com", "secret-key-1").directory)
        assertNotEquals(first.directory, second.directory)
        assertNotEquals(first.directory, otherHost.directory)
        assertFalse(first.directory.toString().contains("secret"))
    }

    @Test
    fun invalidNames() {
        assertThrows(IllegalArgumentException::class.java) { store.file("../value") }
        assertThrows(IllegalArgumentException::class.java) { store.subStore("a/b") }
    }
}
