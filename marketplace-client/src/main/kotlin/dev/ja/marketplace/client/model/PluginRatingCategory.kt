/*
 * Copyright (c) 2026 Joachim Ansorg.
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
 * A category, which reviews rate separately, e.g. the performance of the plugin.
 * Categories, which the Marketplace adds later, are an [UnknownRatingCategory].
 */
@Serializable(PluginRatingCategorySerializer::class)
sealed interface PluginRatingCategory {
    /** The ID of the API, e.g. `PERFORMANCE`. */
    val id: String
    val displayName: String?

    companion object {
        private val KNOWN_CATEGORIES = listOf(
            AvailableFeaturesRating,
            DocumentationQualityRating,
            IntegrationRating,
            PerformanceRating,
            PricingPolicyRating,
            UserInterfaceRating,
        )

        private val categoryMapping = ConcurrentHashMap<String, PluginRatingCategory>()

        fun findById(id: String): PluginRatingCategory {
            return categoryMapping.computeIfAbsent(id) {
                KNOWN_CATEGORIES.firstOrNull { it.id == id } ?: UnknownRatingCategory(id)
            }
        }
    }
}

data class UnknownRatingCategory(override val id: String) : PluginRatingCategory {
    override val displayName: String?
        get() = null

    override fun equals(other: Any?): Boolean {
        return id == (other as? UnknownRatingCategory)?.id
    }

    override fun hashCode(): Int {
        return Objects.hashCode(id)
    }
}

data object AvailableFeaturesRating : PluginRatingCategory {
    override val id: String = "AVAILABLE_FEATURES"
    override val displayName: String = "Available features"
}

data object DocumentationQualityRating : PluginRatingCategory {
    override val id: String = "DOCUMENTATION_QUALITY"
    override val displayName: String = "Documentation quality"
}

data object IntegrationRating : PluginRatingCategory {
    override val id: String = "INTEGRATION"
    override val displayName: String = "Integration"
}

data object PerformanceRating : PluginRatingCategory {
    override val id: String = "PERFORMANCE"
    override val displayName: String = "Performance"
}

data object PricingPolicyRating : PluginRatingCategory {
    override val id: String = "PRICING_POLICY"
    override val displayName: String = "Pricing policy"
}

data object UserInterfaceRating : PluginRatingCategory {
    override val id: String = "USER_INTERFACE"
    override val displayName: String = "User interface"
}

object PluginRatingCategorySerializer : KSerializer<PluginRatingCategory> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("pluginRatingCategory", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PluginRatingCategory {
        return PluginRatingCategory.findById(decoder.decodeString())
    }

    override fun serialize(encoder: Encoder, value: PluginRatingCategory) {
        encoder.encodeString(value.id)
    }
}
