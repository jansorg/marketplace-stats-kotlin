/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MarketplaceProgram(@Transient val jsonId: String) {
    @SerialName("STUDENT")
    Student("STUDENT"),

    @SerialName("GRADUATION")
    FormerStudents("GRADUATION"),

    @SerialName("CLASSROOM")
    ClassroomAssistance("CLASSROOM"),

    @SerialName("DEVELOPER_RECOGNITION")
    DeveloperRecognition("DEVELOPER_RECOGNITION"),

    @SerialName("NON_PROFIT")
    NonProfit("NON_PROFIT"),

    @SerialName("OPEN_SOURCE")
    OpenSource("OPEN_SOURCE"),

    @SerialName("EDUCATIONAL_ORGANIZATION")
    EduOrganizations("EDUCATIONAL_ORGANIZATION"),

    @SerialName("START_UP")
    Startups("START_UP"),

    @SerialName("TRAINING")
    Bootcamps("TRAINING"),

    @SerialName("USER_GROUP")
    UserGroups("USER_GROUP")
}