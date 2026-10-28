/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.netty.channel.ChannelFutureListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Runs [block] and cancels it, when the client closes the connection of the call, e.g. when the browser aborts the
 * request of the values of a chart, because the chart dialog was closed. Ktor doesn't cancel a call then, the data,
 * which the call loads from the Marketplace, would be loaded until the end. The cancellation cancels the requests of
 * the client, see `CaffeineCacheMarketplaceClient`.
 *
 * Without the Netty engine, e.g. in tests, the block isn't cancelled.
 */
suspend fun <T> ApplicationCall.cancelOnDisconnect(block: suspend CoroutineScope.() -> T): T = coroutineScope {
    val channel = ((this@cancelOnDisconnect as? RoutingCall)?.pipelineCall?.engineCall as? NettyApplicationCall)?.context?.channel()
    if (channel == null) {
        return@coroutineScope block()
    }
    val work = async { block() }
    val listener = ChannelFutureListener { work.cancel(CancellationException("The client closed the connection")) }
    channel.closeFuture().addListener(listener)
    try {
        work.await()
    } finally {
        channel.closeFuture().removeListener(listener)
    }
}
