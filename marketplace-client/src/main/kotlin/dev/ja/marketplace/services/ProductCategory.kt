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
import java.util.concurrent.ConcurrentHashMap

@Serializable(ProductCategory.Serializer::class)
sealed interface ProductCategory {
    val id: String

    companion object {
        private val categories = listOf(
            IdeCategory,
            TeamToolCategory,
            DotNetToolCategory,
            UtilitiesCategory,
            LanguageCategory,
            VisualStudioCategory
        )

        private val categoryMapping = ConcurrentHashMap<String, ProductCategory>()

        fun fingById(id: String): ProductCategory {
            return categoryMapping.computeIfAbsent(id) {
                categories.firstOrNull { id == it.id } ?: UnknownIdeCategory(id)
            }
        }
    }

    object Serializer : KSerializer<ProductCategory> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("productCategory", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): ProductCategory {
            return ProductCategory.fingById(decoder.decodeString())
        }

        override fun serialize(encoder: Encoder, value: ProductCategory) {
            encoder.encodeString(value.id)
        }
    }
}

data object IdeCategory : ProductCategory {
    override val id: String = "IDE"
}

data object TeamToolCategory : ProductCategory {
    override val id: String = "Team tool"
}

data object DotNetToolCategory : ProductCategory {
    override val id: String = ".NET tool"
}

data object UtilitiesCategory : ProductCategory {
    override val id: String = "Utilities"
}

data object LanguageCategory : ProductCategory {
    override val id: String = "Language"
}

data object VisualStudioCategory : ProductCategory {
    override val id: String = "Visual Studio"
}

data class UnknownIdeCategory(override val id: String) : ProductCategory
