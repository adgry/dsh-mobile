package com.dshmobile.app.data

import com.dshmobile.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Pre-filled with the gateway this build was developed against, so a fresh install just works. */
const val DEFAULT_BASE_URL = "https://token.sensenova.cn/v1"
/**
 * Injected at build time from `secrets.properties` (or the `DSH_DEFAULT_API_KEY` env var), so the
 * repository never carries a working credential. Empty in a build without one.
 */
val DEFAULT_API_KEY: String = BuildConfig.DEFAULT_API_KEY
const val DEFAULT_MODEL = "deepseek-v4-flash"

/** Public, unauthenticated: GitHub serves release assets without a token. */
const val DEFAULT_UPDATE_URL = "https://api.github.com/repos/adgry/dsh-mobile/releases/latest"

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** One OpenAI-compatible endpoint. Several can be configured and switched between. */
@Serializable
data class Provider(
    val id: String = newId(),
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    /** Remembered so switching back to a service restores the model that was in use. */
    val lastModel: String = "",
) {
    val normalizedBaseUrl: String get() = baseUrl.trim().trimEnd('/')

    val displayName: String
        get() = name.ifBlank {
            normalizedBaseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')
                .ifBlank { "未命名服务" }
        }

    val isUsable: Boolean get() = normalizedBaseUrl.isNotEmpty() && apiKey.isNotBlank()
}

fun defaultProvider(): Provider = Provider(
    name = "SenseNova Token",
    baseUrl = DEFAULT_BASE_URL,
    apiKey = DEFAULT_API_KEY,
    lastModel = DEFAULT_MODEL,
)

@Serializable
data class AppSettings(
    val providers: List<Provider> = emptyList(),
    val activeProviderId: String = "",
    val model: String = DEFAULT_MODEL,
    val systemPrompt: String = "",
    /** Only sent when [useTemperature] is on; some gateways reject unsupported sampling params. */
    val temperature: Float = 0.6f,
    val useTemperature: Boolean = false,
    /** 0 means "don't send max_tokens" — reasoning models spend the budget on thinking first. */
    val maxTokens: Int = 0,
    val stream: Boolean = true,
    val showReasoning: Boolean = true,
    /**
     * Context is budgeted in tokens, not messages. When [autoContextBudget] is on the budget is
     * derived from the model's own window; otherwise [contextBudgetTokens] caps it explicitly.
     */
    val autoContextBudget: Boolean = true,
    val contextBudgetTokens: Int = 32_768,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val sendOnEnter: Boolean = false,
    /** Where to look for a newer build. Empty disables self-update entirely. */
    /**
     * Where 检查更新 looks. Defaults to this project's own GitHub releases; a `releases/latest`
     * payload and the native `update.json` shape are both understood, so a self-hosted feed works
     * by just replacing this.
     */
    val updateUrl: String = DEFAULT_UPDATE_URL,
    val autoCheckUpdates: Boolean = true,
    val lastUpdateCheck: Long = 0L,
) {
    val activeProvider: Provider?
        get() = providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()

    val baseUrl: String get() = activeProvider?.normalizedBaseUrl.orEmpty()

    val apiKey: String get() = activeProvider?.apiKey.orEmpty()

    val isConfigured: Boolean
        get() = activeProvider?.isUsable == true && model.isNotBlank()

    /**
     * How many tokens of history to resend. In auto mode this leaves room for the reply: the
     * model's window minus its maximum output, with a safety margin, so a long answer can't be
     * squeezed out by the history that requested it.
     */
    fun contextBudget(contextLength: Int, maxOutputLength: Int): Int {
        if (!autoContextBudget) {
            return if (contextLength > 0) contextBudgetTokens.coerceAtMost(contextLength) else contextBudgetTokens
        }
        if (contextLength <= 0) return 32_768
        val reserve = if (maxOutputLength > 0) maxOutputLength else contextLength / 8
        return (contextLength - reserve).coerceAtLeast(4_096).let { (it * 0.9f).toInt() }
    }
}

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
    isLenient = true
}

/**
 * Settings live in one small JSON file in app-private storage. Reads are synchronous at
 * construction (a few hundred bytes); writes are coalesced onto the IO dispatcher so rapid
 * slider drags don't turn into a write storm.
 */
class SettingsStore(private val file: File, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(readFromDisk())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val current: AppSettings get() = _state.value

    private val writes = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            while (true) {
                writes.receive()
                delay(150)
                while (writes.tryReceive().isSuccess) Unit
                runCatching { writeToDisk(_state.value) }
            }
        }
    }

    fun update(block: (AppSettings) -> AppSettings) {
        _state.value = normalize(block(_state.value))
        writes.trySend(Unit)
    }

    private fun readFromDisk(): AppSettings = runCatching {
        if (!file.exists()) return@runCatching normalize(AppSettings())
        val text = file.readText()
        val decoded = AppJson.decodeFromString<AppSettings>(text)
        normalize(migrate(decoded, text))
    }.getOrElse { normalize(AppSettings()) }

    /**
     * Settings written before multi-service support carried a single `baseUrl`/`apiKey` pair at the
     * top level. Those keys are gone from [AppSettings], so they are read back out of the raw JSON
     * and folded into the first provider rather than silently dropping someone's key.
     */
    private fun migrate(decoded: AppSettings, raw: String): AppSettings {
        if (decoded.providers.isNotEmpty()) return decoded
        val legacy = runCatching {
            val root = AppJson.parseToJsonElement(raw).jsonObject
            val url = root["baseUrl"]?.jsonPrimitive?.content?.trim().orEmpty()
            val key = root["apiKey"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (url.isEmpty() && key.isEmpty()) null else url to key
        }.getOrNull() ?: return decoded

        val (url, key) = legacy
        val migrated = Provider(
            name = if (url == DEFAULT_BASE_URL) "SenseNova Token" else "",
            baseUrl = url.ifEmpty { DEFAULT_BASE_URL },
            apiKey = key,
            lastModel = decoded.model,
        )
        return decoded.copy(providers = listOf(migrated), activeProviderId = migrated.id)
    }

    /** Guarantees there is always at least one provider and that the active id points at a real one. */
    private fun normalize(settings: AppSettings): AppSettings {
        val providers = settings.providers.ifEmpty { listOf(defaultProvider()) }
        val active = providers.firstOrNull { it.id == settings.activeProviderId } ?: providers.first()
        val model = settings.model.ifBlank { active.lastModel.ifBlank { DEFAULT_MODEL } }
        return settings.copy(
            providers = providers,
            activeProviderId = active.id,
            model = model,
        )
    }

    private fun writeToDisk(value: AppSettings) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(AppJson.encodeToString(value))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
