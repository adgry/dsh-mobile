package com.dshmobile.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    @Test
    fun `auto budget leaves room for the reply`() {
        val settings = AppSettings(autoContextBudget = true)
        val budget = settings.contextBudget(contextLength = 1_048_576, maxOutputLength = 65_536)
        assertTrue("budget $budget should reserve the output window", budget < 1_048_576 - 65_536 + 1)
        assertTrue("budget $budget should still be most of the window", budget > 800_000)
    }

    @Test
    fun `auto budget copes with an unknown window`() {
        assertEquals(32_768, AppSettings(autoContextBudget = true).contextBudget(0, 0))
    }

    @Test
    fun `manual budget never exceeds the model window`() {
        val settings = AppSettings(autoContextBudget = false, contextBudgetTokens = 512_000)
        assertEquals(262_144, settings.contextBudget(contextLength = 262_144, maxOutputLength = 0))
    }

    @Test
    fun `manual budget is honoured when it fits`() {
        val settings = AppSettings(autoContextBudget = false, contextBudgetTokens = 32_768)
        assertEquals(32_768, settings.contextBudget(1_048_576, 65_536))
    }

    @Test
    fun `active provider falls back to the first when the id is stale`() {
        val a = Provider(id = "a", baseUrl = "https://a/v1", apiKey = "k")
        val b = Provider(id = "b", baseUrl = "https://b/v1", apiKey = "k")
        val settings = AppSettings(providers = listOf(a, b), activeProviderId = "gone")
        assertEquals("a", settings.activeProvider?.id)
        assertEquals("https://a/v1", settings.baseUrl)
    }

    @Test
    fun `provider display name falls back to its host`() {
        val provider = Provider(baseUrl = "https://token.sensenova.cn/v1", apiKey = "k")
        assertEquals("token.sensenova.cn", provider.displayName)
        assertEquals("命名优先", Provider(name = "命名优先", baseUrl = "https://x/v1").displayName)
    }

    @Test
    fun `a provider without a key is not usable`() {
        assertFalse(Provider(baseUrl = "https://x/v1").isUsable)
        assertFalse(Provider(apiKey = "k").isUsable)
        assertTrue(Provider(baseUrl = "https://x/v1", apiKey = "k").isUsable)
    }

    @Test
    fun `trailing slashes are stripped from the base url`() {
        assertEquals("https://x/v1", Provider(baseUrl = "  https://x/v1/  ").normalizedBaseUrl)
    }
}
