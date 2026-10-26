/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProductFamilyTest {
    @Test
    fun knownAndNewFamilies() {
        val families = Json.decodeFromString(ListSerializer(ProductFamily.serializer()), """["intellij", "teamcity_recipes", "new_family"]""")
        assertEquals(listOf(IntelliJFamily, TeamCityRecipesFamily, UnknownProductFamily("new_family")), families)
        assertEquals(""""new_family"""", Json.encodeToString(ProductFamily.serializer(), families[2]))
        assertEquals(DotNetFamily, ProductFamily.findById("dotnet"))
    }
}
