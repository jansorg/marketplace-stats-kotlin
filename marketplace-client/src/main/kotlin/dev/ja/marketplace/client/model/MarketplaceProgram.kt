/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import dev.ja.marketplace.client.TolerantEnumSerializer
import kotlinx.serialization.Serializable

/**
 * A program of the Marketplace, in which a plugin takes part. Programs, which the client doesn't know, are [Unknown].
 */
@Serializable(MarketplaceProgramSerializer::class)
enum class MarketplaceProgram(val jsonId: String) {
    Student("STUDENT"),
    FormerStudents("GRADUATION"),
    ClassroomAssistance("CLASSROOM"),
    DeveloperRecognition("DEVELOPER_RECOGNITION"),
    NonProfit("NON_PROFIT"),
    OpenSource("OPEN_SOURCE"),
    EduOrganizations("EDUCATIONAL_ORGANIZATION"),
    Startups("START_UP"),
    Bootcamps("TRAINING"),
    UserGroups("USER_GROUP"),

    /** A program, which the Marketplace added later. */
    Unknown("UNKNOWN"),
}

object MarketplaceProgramSerializer : TolerantEnumSerializer<MarketplaceProgram>(
    "MarketplaceProgram", MarketplaceProgram.entries, MarketplaceProgram.Unknown, MarketplaceProgram::jsonId,
)
