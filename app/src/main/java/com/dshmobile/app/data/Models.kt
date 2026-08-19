package com.dshmobile.app.data

import com.dshmobile.app.util.TokenEstimate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

@Serializable
enum class Role {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("system") SYSTEM,
    ;

    val wire: String
        get() = when (this) {
            USER -> "user"
            ASSISTANT -> "assistant"
            SYSTEM -> "system"
        }
}

@Serializable
enum class AttachmentKind {
    /** Sent as an `image_url` part; needs a model that accepts image input. */
    IMAGE,

    /** Extracted text, inlined into the prompt. Works on any text model. */
    DOCUMENT,
}

/**
 * Something the user attached to a message. Payloads live in their own file under
 * `filesDir/attachments/` so a conversation's JSON stays small and cheap to rewrite on every
 * streamed token; [fileName] is the key into that directory.
 */
@Serializable
data class Attachment(
    val id: String = newId(),
    val kind: AttachmentKind = AttachmentKind.IMAGE,
    val fileName: String,
    val mimeType: String = "image/jpeg",
    val displayName: String = "",
    val byteCount: Long = 0L,
    /** Images only. */
    val width: Int = 0,
    val height: Int = 0,
    /** Documents only: characters of extracted text, and whether the file was cut short. */
    val charCount: Int = 0,
    val truncated: Boolean = false,
    /** Estimated prompt cost, computed once at attach time. */
    val tokenEstimate: Int = 0,
) {
    val isImage: Boolean get() = kind == AttachmentKind.IMAGE
    val isDocument: Boolean get() = kind == AttachmentKind.DOCUMENT

    val label: String get() = displayName.ifBlank { if (isImage) "图片" else "文件" }
}

/**
 * Prompt cost of an attachment. Conversations stored before attachments carried an estimate fall
 * back to the flat image rate rather than counting as free.
 */
val Attachment.tokens: Int
    get() = when {
        tokenEstimate > 0 -> tokenEstimate
        isImage -> TokenEstimate.IMAGE_TOKENS
        else -> 0
    }

val List<Attachment>.tokens: Int get() = sumOf { it.tokens }

@Serializable
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cachedTokens: Int = 0,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
    val isEmpty: Boolean get() = promptTokens == 0 && completionTokens == 0
}

@Serializable
data class ChatMessage(
    val id: String = newId(),
    val role: Role,
    val content: String = "",
    /** DeepSeek-style `reasoning_content`, kept separate so it can be collapsed away. */
    val reasoning: String = "",
    val attachments: List<Attachment> = emptyList(),
    val model: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** Wall-clock time the assistant took, in ms. Zero for user messages. */
    val elapsedMs: Long = 0L,
    val usage: Usage = Usage(),
    val finishReason: String = "",
    /** Non-null when the turn failed; [content] then holds whatever arrived before the failure. */
    val error: String? = null,
) {
    val isBlank: Boolean get() = content.isBlank() && reasoning.isBlank() && attachments.isEmpty()

    /** True when the provider stopped because it hit the output cap, so a continuation makes sense. */
    val truncatedByLength: Boolean get() = finishReason == "length" && error == null
}

@Serializable
data class Conversation(
    val id: String = newId(),
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Model used for the most recent turn; new turns default to it. */
    val model: String = "",
    /** Which configured service produced this conversation, so reopening it restores the pair. */
    val providerId: String = "",
    /** Overrides the global system prompt for this conversation when non-blank. */
    val systemPrompt: String = "",
    val pinned: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
) {
    val displayTitle: String
        get() = title.ifBlank {
            messages.firstOrNull { it.role == Role.USER }
                ?.content
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.take(40)
                ?: "新对话"
        }

    val preview: String
        get() = messages.lastOrNull { it.role == Role.ASSISTANT && it.content.isNotBlank() }
            ?.content
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(80)
            .orEmpty()
}

/** One entry from `GET /v1/models`. */
@Serializable
data class ModelInfo(
    val id: String,
    val name: String = "",
    val description: String = "",
    val contextLength: Int = 0,
    val maxOutputLength: Int = 0,
    val inputModalities: List<String> = emptyList(),
    val outputModalities: List<String> = emptyList(),
    val supportedFeatures: List<String> = emptyList(),
    val supportedSamplingParameters: List<String> = emptyList(),
) {
    val acceptsImages: Boolean get() = inputModalities.any { it.equals("image", true) }
    val emitsText: Boolean get() = outputModalities.isEmpty() || outputModalities.any { it.equals("text", true) }
    val emitsImages: Boolean get() = outputModalities.any { it.equals("image", true) }
    val hasReasoning: Boolean get() = supportedFeatures.any { it.equals("reasoning", true) }
    val supportsTemperature: Boolean
        get() = supportedSamplingParameters.isEmpty() ||
            supportedSamplingParameters.any { it.equals("temperature", true) }
    val label: String get() = name.ifBlank { id }
}
