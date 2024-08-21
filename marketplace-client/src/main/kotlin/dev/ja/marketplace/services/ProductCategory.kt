/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable(ProductCategorySerializer::class)
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