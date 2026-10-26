/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * The family of JetBrains products of a plugin, e.g. IntelliJ-based IDEs.
 * Families, which the Marketplace adds later, are an [UnknownProductFamily].
 */
@Serializable(ProductFamilySerializer::class)
sealed interface ProductFamily {
    /** The ID of the API, e.g. `intellij`. */
    val id: String
    val displayName: String?

    companion object {
        private val KNOWN_FAMILIES = listOf(
            IntelliJFamily,
            TeamCityFamily,
            TeamCityRecipesFamily,
            HubFamily,
            FleetFamily,
            DotNetFamily,
            SpaceFamily,
            ToolBoxFamily,
            EduFamily,
            YouTrackFamily,
        )

        private val familyMapping = ConcurrentHashMap<String, ProductFamily>()

        fun findById(id: String): ProductFamily {
            return familyMapping.computeIfAbsent(id) {
                KNOWN_FAMILIES.firstOrNull { it.id == id } ?: UnknownProductFamily(id)
            }
        }
    }
}

data class UnknownProductFamily(override val id: String) : ProductFamily {
    override val displayName: String?
        get() = null

    override fun equals(other: Any?): Boolean {
        return id == (other as? UnknownProductFamily)?.id
    }

    override fun hashCode(): Int {
        return Objects.hashCode(id)
    }
}

data object IntelliJFamily : ProductFamily {
    override val id: String = "intellij"
    override val displayName: String = "IntelliJ"
}

data object TeamCityFamily : ProductFamily {
    override val id: String = "teamcity"
    override val displayName: String = "TeamCity"
}

data object TeamCityRecipesFamily : ProductFamily {
    override val id: String = "teamcity_recipes"
    override val displayName: String = "TeamCity Recipes"
}

data object HubFamily : ProductFamily {
    override val id: String = "hub"
    override val displayName: String = "Hub"
}

data object FleetFamily : ProductFamily {
    override val id: String = "fleet"
    override val displayName: String = "Fleet"
}

data object DotNetFamily : ProductFamily {
    override val id: String = "dotnet"
    override val displayName: String = ".NET"
}

data object SpaceFamily : ProductFamily {
    override val id: String = "space"
    override val displayName: String = "Space"
}

data object ToolBoxFamily : ProductFamily {
    override val id: String = "toolbox"
    override val displayName: String = "Toolbox"
}

data object EduFamily : ProductFamily {
    override val id: String = "edu"
    override val displayName: String = "Education"
}

data object YouTrackFamily : ProductFamily {
    override val id: String = "youtrack"
    override val displayName: String = "YouTrack"
}

object ProductFamilySerializer : KSerializer<ProductFamily> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("productFamily", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ProductFamily {
        return ProductFamily.findById(decoder.decodeString())
    }

    override fun serialize(encoder: Encoder, value: ProductFamily) {
        encoder.encodeString(value.id)
    }
}
