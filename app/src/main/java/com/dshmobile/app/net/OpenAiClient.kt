package com.dshmobile.app.net

import com.dshmobile.app.data.ModelInfo
import com.dshmobile.app.data.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A message on its way out to the API. [images] holds `data:` URLs. */
data class OutgoingMessage(
    val role: String,
    val text: String,
    val images: List<String> = emptyList(),
)

sealed interface StreamEvent {
    /** Visible answer text. */
    data class Content(val text: String) : StreamEvent

    /** DeepSeek's `reasoning_content` — the thinking trace. */
    data class Reasoning(val text: String) : StreamEvent

    data class Tokens(val usage: Usage) : StreamEvent

    data class Finished(val reason: String) : StreamEvent
}

/**
 * Carries a message that is safe to show a user. [status] is the HTTP code when there was one,
 * and [retryable] marks the failures where trying the same turn again is reasonable.
 */
class ApiException(
    message: String,
    val status: Int = 0,
    val retryable: Boolean = false,
) : IOException(message)

class OpenAiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** No read/call timeout: a reasoning model can think for a long time between chunks. */
    private val streaming: OkHttpClient = base.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val blocking: OkHttpClient = base.newBuilder()
        .readTimeout(300, TimeUnit.SECONDS)
        .callTimeout(360, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------ models

    suspend fun listModels(baseUrl: String, apiKey: String): List<ModelInfo> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint(baseUrl, "models"))
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Accept", "application/json")
                .get()
                .build()

            blocking.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw errorFor(response.code, body)
                val parsed = runCatching { json.decodeFromString<ModelListResponse>(body) }
                    .getOrElse { throw ApiException("模型列表解析失败：${it.message}") }
                parsed.data.map { it.toModelInfo() }
            }
        }

    // -------------------------------------------------------------------- chat

    fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<OutgoingMessage>,
        temperature: Float?,
        maxTokens: Int,
    ): Flow<StreamEvent> = channelFlow {
        val body = requestBody(model, messages, stream = true, temperature = temperature, maxTokens = maxTokens)
        val request = chatRequest(baseUrl, apiKey, body, sse = true)
        val call = streaming.newCall(request)

        val worker = launch(Dispatchers.IO) {
            call.execute().use { response ->
                val source = response.body?.source()
                if (!response.isSuccessful || source == null) {
                    val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                    throw errorFor(response.code, text)
                }

                var sawAnything = false
                while (!source.exhausted()) {
                    val line = source.readUtf8LineStrict()
                    if (line.isEmpty()) continue

                    // Comment / keep-alive frames per the SSE spec.
                    if (line.startsWith(":")) continue
                    val payload = when {
                        line.startsWith("data:") -> line.removePrefix("data:").trim()
                        // Tolerate gateways that emit bare JSON lines instead of SSE frames.
                        line.startsWith("{") -> line
                        else -> continue
                    }
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break

                    val chunk = runCatching { json.decodeFromString<ChatChunk>(payload) }.getOrNull()
                        ?: continue

                    chunk.error?.let { throw ApiException(it.readableMessage()) }

                    chunk.usage?.let { send(StreamEvent.Tokens(it.toUsage())) }

                    val choice = chunk.choices.firstOrNull()
                    if (choice != null) {
                        choice.delta?.reasoningText()?.takeIf { it.isNotEmpty() }?.let {
                            sawAnything = true
                            send(StreamEvent.Reasoning(it))
                        }
                        choice.delta?.content?.takeIf { it.isNotEmpty() }?.let {
                            sawAnything = true
                            send(StreamEvent.Content(it))
                        }
                        choice.finishReason?.takeIf { it.isNotBlank() }?.let {
                            send(StreamEvent.Finished(it))
                        }
                    }
                }

                if (!sawAnything) send(StreamEvent.Finished("empty"))
            }
        }

        try {
            worker.join()
        } finally {
            // On cancellation the reader is parked in a blocking socket read; only closing the
            // connection wakes it, so `stop` has to reach the socket rather than the coroutine.
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /** Non-streaming completion, for when the user turns streaming off. */
    suspend fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<OutgoingMessage>,
        temperature: Float?,
        maxTokens: Int,
    ): CompletionResult = withContext(Dispatchers.IO) {
        val body = requestBody(model, messages, stream = false, temperature = temperature, maxTokens = maxTokens)
        blocking.newCall(chatRequest(baseUrl, apiKey, body, sse = false)).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFor(response.code, text)
            val parsed = runCatching { json.decodeFromString<ChatCompletion>(text) }
                .getOrElse { throw ApiException("响应解析失败：${it.message}") }
            parsed.error?.let { throw ApiException(it.readableMessage()) }
            val choice = parsed.choices.firstOrNull()
            CompletionResult(
                content = choice?.message?.content.orEmpty(),
                reasoning = choice?.message?.reasoningText().orEmpty(),
                finishReason = choice?.finishReason.orEmpty(),
                usage = parsed.usage?.toUsage() ?: Usage(),
            )
        }
    }

    data class CompletionResult(
        val content: String,
        val reasoning: String,
        val finishReason: String,
        val usage: Usage,
    )

    // --------------------------------------------------------------- internals

    private fun endpoint(baseUrl: String, path: String): String {
        val root = baseUrl.trim().trimEnd('/')
        require(root.isNotEmpty()) { "base URL is empty" }
        return "$root/$path"
    }

    private fun chatRequest(baseUrl: String, apiKey: String, body: JsonObject, sse: Boolean): Request =
        Request.Builder()
            .url(endpoint(baseUrl, "chat/completions"))
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")
            .header("Accept", if (sse) "text/event-stream" else "application/json")
            .apply { if (sse) header("Cache-Control", "no-cache") }
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

    private fun requestBody(
        model: String,
        messages: List<OutgoingMessage>,
        stream: Boolean,
        temperature: Float?,
        maxTokens: Int,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", stream)
        if (stream) {
            putJsonObject("stream_options") { put("include_usage", true) }
        }
        temperature?.let { put("temperature", it) }
        if (maxTokens > 0) put("max_tokens", maxTokens)
        putJsonArray("messages") {
            messages.forEach { message ->
                addJsonObject {
                    put("role", message.role)
                    if (message.images.isEmpty()) {
                        put("content", message.text)
                    } else {
                        putJsonArray("content") {
                            if (message.text.isNotBlank()) {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", message.text)
                                }
                            }
                            message.images.forEach { url ->
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", url) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Error bodies vary between gateways and `code` is sometimes a string, sometimes a number,
     * so the message is dug out of the raw tree rather than through a strict DTO.
     */
    private fun errorFor(status: Int, body: String): ApiException {
        val detail = runCatching {
            val root = Json.parseToJsonElement(body).jsonObject
            val error = root["error"]?.jsonObject
            val message = (error?.get("message") ?: root["message"])?.jsonPrimitive?.content
            val code = (error?.get("code") ?: root["code"])?.jsonPrimitive?.content
            when {
                message.isNullOrBlank() -> null
                code.isNullOrBlank() -> message
                else -> "$message（$code）"
            }
        }.getOrNull()

        val fallback = when (status) {
            401, 403 -> "认证失败，请检查 API Key"
            404 -> "接口不存在，请检查 Base URL"
            429 -> "请求过于频繁或额度不足，请稍后重试"
            in 500..599 -> "服务端错误（HTTP $status）"
            0 -> "网络请求失败"
            else -> "请求失败（HTTP $status）"
        }
        val retryable = status == 429 || status in 500..599 || status == 0
        return ApiException(detail ?: fallback, status = status, retryable = retryable)
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

// ------------------------------------------------------------------ wire types

@Serializable
private data class ModelListResponse(val data: List<WireModel> = emptyList())

@Serializable
private data class WireModel(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    @SerialName("context_length") val contextLength: Int = 0,
    @SerialName("max_output_length") val maxOutputLength: Int = 0,
    @SerialName("input_modalities") val inputModalities: List<String> = emptyList(),
    @SerialName("output_modalities") val outputModalities: List<String> = emptyList(),
    @SerialName("supported_features") val supportedFeatures: List<String> = emptyList(),
    @SerialName("supported_sampling_parameters") val samplingParameters: List<String> = emptyList(),
) {
    fun toModelInfo() = ModelInfo(
        id = id,
        name = name,
        description = description,
        contextLength = contextLength,
        maxOutputLength = maxOutputLength,
        inputModalities = inputModalities,
        outputModalities = outputModalities,
        supportedFeatures = supportedFeatures,
        supportedSamplingParameters = samplingParameters,
    )
}

@Serializable
private data class WireError(
    val message: String = "",
    val type: String = "",
) {
    fun readableMessage(): String = message.ifBlank { type.ifBlank { "未知错误" } }
}

@Serializable
private data class WireDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
) {
    fun reasoningText(): String? = reasoningContent ?: reasoning
}

@Serializable
private data class ChunkChoice(
    val index: Int = 0,
    val delta: WireDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class ChatChunk(
    /** Empty on the trailing usage-only frame this gateway sends. */
    val choices: List<ChunkChoice> = emptyList(),
    val usage: WireUsage? = null,
    val error: WireError? = null,
)

@Serializable
private data class WireMessage(
    val role: String = "",
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
) {
    fun reasoningText(): String? = reasoningContent ?: reasoning
}

@Serializable
private data class CompletionChoice(
    val message: WireMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class ChatCompletion(
    val choices: List<CompletionChoice> = emptyList(),
    val usage: WireUsage? = null,
    val error: WireError? = null,
)

@Serializable
private data class CompletionTokenDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int = 0,
)

@Serializable
private data class PromptTokenDetails(
    @SerialName("cached_tokens") val cachedTokens: Int = 0,
)

@Serializable
private data class WireUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("completion_tokens_details") val completionDetails: CompletionTokenDetails? = null,
    @SerialName("prompt_tokens_details") val promptDetails: PromptTokenDetails? = null,
) {
    fun toUsage() = Usage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        reasoningTokens = completionDetails?.reasoningTokens ?: 0,
        cachedTokens = promptDetails?.cachedTokens ?: 0,
    )
}
