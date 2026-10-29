/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Serializable(ProductDownloadType.Serializer::class)
sealed interface ProductDownloadType {
    val id: String
    val displayName: String?

    companion object {
        private val KNOWN_TYPES = listOf(
            LinuxDownloadType,
            LinuxArm64DownloadType,
            MacIntelDownloadType,
            MacM1DownloadType,
            WindowsDownloadType,
            WindowsArm64DownloadType,
            WindowsZipDownloadType,
            ThirdPartyLibrariesJson,
        )

        private val typeMapping = ConcurrentHashMap<String, ProductDownloadType>()

        fun findById(id: String): ProductDownloadType {
            return typeMapping.computeIfAbsent(id) {
                KNOWN_TYPES.firstOrNull { it.id == id } ?: UnknownDownloadType(id)
            }
        }
    }

    object Serializer : KSerializer<ProductDownloadType> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("productDownloadType", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): ProductDownloadType {
            return ProductDownloadType.findById(decoder.decodeString())
        }

        override fun serialize(encoder: Encoder, value: ProductDownloadType) {
            encoder.encodeString(value.id)
        }
    }
}

data class UnknownDownloadType(override val id: String) : ProductDownloadType {
    override val displayName: String?
        get() = null

    override fun equals(other: Any?): Boolean {
        return id == (other as? UnknownDownloadType)?.id
    }

    override fun hashCode(): Int {
        return Objects.hashCode(id)
    }
}

data object LinuxDownloadType : ProductDownloadType {
    override val id: String = "linux"
    override val displayName: String = "Linux x64"
}

data object LinuxArm64DownloadType : ProductDownloadType {
    override val id: String = "linuxARM64"
    override val displayName: String = "Linux ARM64"
}

data object MacIntelDownloadType : ProductDownloadType {
    override val id: String = "mac"
    override val displayName: String = "Mac Intel"
}

data object MacM1DownloadType : ProductDownloadType {
    override val id: String = "macM1"
    override val displayName: String = "Mac Apple Silicon"
}

data object WindowsDownloadType : ProductDownloadType {
    override val id: String = "windows"
    override val displayName: String = "Windows (exe)"
}

data object WindowsArm64DownloadType : ProductDownloadType {
    override val id: String = "windowsARM64"
    override val displayName: String = "Windows ARM64 (exe)"
}

data object WindowsZipDownloadType : ProductDownloadType {
    override val id: String = "windowsZip"
    override val displayName: String = "Windows (ZIP)"
}

data object ThirdPartyLibrariesJson : ProductDownloadType {
    override val id: String = "thirdPartyLibrariesJson"
    override val displayName: String = "Used Third-Party Libraries"
}

