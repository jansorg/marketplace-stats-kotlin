/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.MarketplaceClient
import dev.ja.marketplace.client.model.PluginPricingModel
import dev.ja.marketplace.exchangeRate.ExchangeRates
import dev.ja.marketplace.mcp.McpTestData
import dev.ja.marketplace.services.Countries
import dev.ja.marketplace.services.JetBrainsServiceClient
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class PluginDataLoaderTest {
    // the prices aren't requested while the loader is created
    private val client = unusedClient<MarketplaceClient>()

    private fun loader(pricingModel: PluginPricingModel): PluginDataLoader {
        val plugin = McpTestData.plugin.copy(pricingModel = pricingModel)
        val ideReleases = IdeReleaseProvider(unusedClient<JetBrainsServiceClient>())
        return PluginDataLoader(client, plugin, Countries(emptyList()), ExchangeRates("USD"), ideReleases)
    }

    @Test
    fun paidPluginsHavePrices() {
        assertNotNull(loader(PluginPricingModel.Paid).pricing())
        assertNotNull(loader(PluginPricingModel.Freemium).pricing())
    }

    @Test
    fun freePluginsHaveNoPrices() {
        assertNull(loader(PluginPricingModel.Free).pricing())
    }

    private inline fun <reified T> unusedClient(): T {
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw UnsupportedOperationException(method.name)
        } as T
    }
}
