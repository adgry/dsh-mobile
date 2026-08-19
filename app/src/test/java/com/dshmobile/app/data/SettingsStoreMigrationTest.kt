package com.dshmobile.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Upgrading must not silently drop somebody's endpoint or key. The pre-multi-service build wrote
 * `baseUrl`/`apiKey` at the top level; those keys no longer exist on [AppSettings], so without the
 * migration step they would be ignored and replaced by the shipped defaults.
 */
class SettingsStoreMigrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Job())

    @After
    fun tearDown() = scope.cancel()

    private fun storeFor(json: String?): SettingsStore {
        val file = File(folder.root, "settings.json")
        if (json != null) file.writeText(json)
        return SettingsStore(file, scope)
    }

    @Test
    fun `legacy single-endpoint settings become one provider`() {
        val store = storeFor(
            """
            {"baseUrl":"https://my.gateway/v1","apiKey":"sk-legacy-key","model":"glm-5.2",
             "systemPrompt":"be terse","temperature":0.6,"useTemperature":false,"maxTokens":0,
             "stream":true,"showReasoning":true,"contextMessages":20,"themeMode":"DARK",
             "sendOnEnter":true}
            """.trimIndent(),
        )
        val settings = store.current

        assertEquals(1, settings.providers.size)
        val provider = settings.providers.single()
        assertEquals("https://my.gateway/v1", provider.baseUrl)
        assertEquals("sk-legacy-key", provider.apiKey)
        assertEquals(provider.id, settings.activeProviderId)
        assertEquals("https://my.gateway/v1", settings.baseUrl)
        assertEquals("sk-legacy-key", settings.apiKey)

        // Everything else the user had set must survive the upgrade too.
        assertEquals("glm-5.2", settings.model)
        assertEquals("be terse", settings.systemPrompt)
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertTrue(settings.sendOnEnter)
    }

    @Test
    fun `a legacy file with no endpoint still yields a usable default`() {
        val store = storeFor("""{"model":"deepseek-v4-flash","themeMode":"LIGHT"}""")
        val settings = store.current
        assertEquals(1, settings.providers.size)
        assertTrue(settings.providers.single().isUsable)
        assertEquals(ThemeMode.LIGHT, settings.themeMode)
    }

    @Test
    fun `a fresh install gets the bundled default provider`() {
        val settings = storeFor(null).current
        assertEquals(1, settings.providers.size)
        assertEquals(DEFAULT_BASE_URL, settings.providers.single().baseUrl)
        assertEquals(DEFAULT_MODEL, settings.model)
        assertTrue(settings.isConfigured)
    }

    @Test
    fun `corrupt json falls back instead of crashing`() {
        val settings = storeFor("{not json at all").current
        assertNotNull(settings.activeProvider)
        assertTrue(settings.isConfigured)
    }

    @Test
    fun `multi-provider settings round-trip unchanged`() {
        val a = Provider(id = "a", name = "A", baseUrl = "https://a/v1", apiKey = "ka", lastModel = "m1")
        val b = Provider(id = "b", name = "B", baseUrl = "https://b/v1", apiKey = "kb")
        val original = AppSettings(providers = listOf(a, b), activeProviderId = "b", model = "m2")
        val json = AppJson.encodeToString(original)

        val settings = storeFor(json).current
        assertEquals(listOf("a", "b"), settings.providers.map { it.id })
        assertEquals("b", settings.activeProviderId)
        assertEquals("m2", settings.model)
    }

    @Test
    fun `a stale active id is repointed at a real provider`() {
        val a = Provider(id = "a", baseUrl = "https://a/v1", apiKey = "ka")
        val json = AppJson.encodeToString(
            AppSettings(providers = listOf(a), activeProviderId = "deleted", model = "m"),
        )
        assertEquals("a", storeFor(json).current.activeProviderId)
    }
}
