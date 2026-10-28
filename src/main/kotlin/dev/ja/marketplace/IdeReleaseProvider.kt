/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.data.releases.IdeMajorReleases
import dev.ja.marketplace.data.releases.MajorRelease
import dev.ja.marketplace.services.JetBrainsProductCode
import dev.ja.marketplace.services.JetBrainsServiceClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Provides the major releases of a JetBrains IDE for the charts. The releases are loaded once a day.
 * If they can't be loaded, the charts are shown without them and loading is retried after a few minutes.
 *
 * @param product The IDE, IntelliJ IDEA by default. Most other JetBrains IDEs are released on the same dates.
 */
class IdeReleaseProvider(
    private val servicesClient: JetBrainsServiceClient,
    private val product: JetBrainsProductCode = JetBrainsProductCode.IntelliJIDEAUltimate,
    private val maxAge: Duration = 1.days,
    private val retryAfter: Duration = 10.minutes,
) {
    private val mutex = Mutex()
    private var releases: List<MajorRelease> = emptyList()
    private var validUntil: Instant = Instant.DISTANT_PAST

    suspend fun majorReleases(): List<MajorRelease> = mutex.withLock {
        val now = Clock.System.now()
        if (now >= validUntil) {
            try {
                // all releases, also those before the launch of the Marketplace with the old versions, e.g. 15.0,
                // the downloads of free plugins start earlier. The charts only show the releases within their dates.
                releases = IdeMajorReleases.find(servicesClient.productReleases(product))
                validUntil = now + maxAge
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                validUntil = now + retryAfter
            }
        }
        releases
    }
}
