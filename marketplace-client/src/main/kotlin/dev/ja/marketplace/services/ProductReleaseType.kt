/*
 * Copyright (c) 2024-2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import dev.ja.marketplace.client.TolerantEnumSerializer
import kotlinx.serialization.Serializable

/**
 * The type of a release of a JetBrains product. Types, which the client doesn't know, are [Unknown].
 * [Unknown] can't be requested.
 */
@Serializable(ProductReleaseType.Serializer::class)
enum class ProductReleaseType(val jsonId: String) {
    Release("release"),
    ReleaseCandidate("rc"),
    EAP("eap"),
    Preview("preview"),

    /** A type, which the JetBrains services added later. */
    Unknown("unknown");

    companion object {
        /** The types, which can be requested. */
        val requestable: List<ProductReleaseType> = entries - Unknown
    }

    object Serializer : TolerantEnumSerializer<ProductReleaseType>(
        "ProductReleaseType", entries, Unknown, ProductReleaseType::jsonId,
    )
}
