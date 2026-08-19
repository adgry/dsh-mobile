package com.dshmobile.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dshmobile.app.AppContainer
import com.dshmobile.app.data.AppSettings
import com.dshmobile.app.data.Attachment
import com.dshmobile.app.data.AttachmentKind
import com.dshmobile.app.data.tokens
import com.dshmobile.app.data.ChatMessage
import com.dshmobile.app.data.Conversation
import com.dshmobile.app.data.ConversationStore
import com.dshmobile.app.data.planContextWindow
import com.dshmobile.app.data.ModelInfo
import com.dshmobile.app.data.Provider
import com.dshmobile.app.data.Role
import com.dshmobile.app.data.SettingsStore
import com.dshmobile.app.data.Usage
import com.dshmobile.app.data.newId
import com.dshmobile.app.net.ApiException
import com.dshmobile.app.net.OpenAiClient
import com.dshmobile.app.net.OutgoingMessage
import com.dshmobile.app.net.StreamEvent
import com.dshmobile.app.update.Updater
import com.dshmobile.app.util.TokenEstimate
import com.dshmobile.app.util.decodeForUpload
import com.dshmobile.app.util.isPdf
import com.dshmobile.app.util.readTextDocument
import com.dshmobile.app.util.renderPdfPages
import com.dshmobile.app.util.formatTimestamp
import com.dshmobile.app.util.toDataUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the composer's context meter shows. */
data class ContextUsage(
    val usedTokens: Int = 0,
    val budgetTokens: Int = 0,
    /** The model's full window — the "200K / 1M" figure. */
    val windowTokens: Int = 0,
    /** Messages the budget will leave out of the next request. */
    val droppedMessages: Int = 0,
)

data class ChatUiState(
    val conversation: Conversation? = null,
    val streaming: Boolean = false,
    val streamingMessageId: String? = null,
    val pendingAttachments: List<Attachment> = emptyList(),
    /** Catalogs keyed by provider id, so the picker can span every configured service. */
    val modelsByProvider: Map<String, List<ModelInfo>> = emptyMap(),
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
    val attaching: Boolean = false,
    val testingProviderId: String? = null,
    val contextUsage: ContextUsage = ContextUsage(),
)

class ChatViewModel(
    private val settingsStore: SettingsStore,
    private val store: ConversationStore,
    private val client: OpenAiClient,
    val updater: Updater,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsStore.state
    val conversations: StateFlow<List<Conversation>> = store.items
    val storeLoaded: StateFlow<Boolean> = store.loaded

    private val _messages = Channel<String>(Channel.BUFFERED)
    val notices = _messages.receiveAsFlow()

    /** Text handed to the app from elsewhere (a share, a voice transcript) for the composer. */
    private val _drafts = Channel<String>(Channel.BUFFERED)
    val drafts = _drafts.receiveAsFlow()

    private var streamJob: Job? = null

    /*
     * Stopping tears down the socket, and the blocking read then throws IOException("Socket
     * closed") from inside the reader coroutine. That can reach the collector ahead of the
     * cancellation, so the intent has to be recorded here rather than inferred from the exception —
     * otherwise pressing stop paints a red error card on a perfectly good partial reply.
     */
    private var stopRequested = false

    init {
        refreshAllModels()
        // A quiet look for a newer build; it stays silent unless something is actually there.
        if (settingsStore.current.autoCheckUpdates) updater.check(manual = false)
    }

    // ------------------------------------------------------------------ models

    fun modelsFor(providerId: String): List<ModelInfo> = _state.value.modelsByProvider[providerId].orEmpty()

    fun modelInfo(id: String): ModelInfo? {
        val settings = settingsStore.current
        modelsFor(settings.activeProviderId).firstOrNull { it.id == id }?.let { return it }
        return _state.value.modelsByProvider.values.asSequence().flatten().firstOrNull { it.id == id }
    }

    fun modelAcceptsImages(id: String): Boolean = modelInfo(id)?.acceptsImages == true

    /**
     * Only true when the catalog says the model *cannot* take images. Before the catalog loads
     * nothing is known, and blocking on a guess would make attaching feel broken.
     */
    fun imagesDisallowed(id: String): Boolean = modelInfo(id)?.acceptsImages == false

    fun refreshModels() = refreshAllModels()

    private fun refreshAllModels() {
        val providers = settingsStore.current.providers.filter { it.isUsable }
        if (providers.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(modelsLoading = true, modelsError = null) }
            val results = providers.map { provider ->
                async {
                    provider.id to runCatching {
                        client.listModels(provider.normalizedBaseUrl, provider.apiKey)
                    }
                }
            }.awaitAll()

            val catalogs = _state.value.modelsByProvider.toMutableMap()
            var firstError: String? = null
            results.forEach { (id, result) ->
                result.fold(
                    onSuccess = { catalogs[id] = it },
                    onFailure = { error -> if (firstError == null) firstError = error.message },
                )
            }
            val activeId = settingsStore.current.activeProviderId
            _state.update {
                it.copy(
                    modelsByProvider = catalogs,
                    modelsLoading = false,
                    // Only surface an error that concerns the service actually in use.
                    modelsError = firstError?.takeIf { _ -> catalogs[activeId].isNullOrEmpty() },
                )
            }
            refreshContextUsage()
        }
    }

    // --------------------------------------------------------------- providers

    fun selectProvider(id: String) {
        val settings = settingsStore.current
        val target = settings.providers.firstOrNull { it.id == id } ?: return
        if (settings.activeProviderId == id) return
        if (_state.value.streaming) stop()

        val catalog = modelsFor(id)
        val model = when {
            target.lastModel.isNotBlank() && catalog.none { it.id == target.lastModel } &&
                catalog.isNotEmpty() -> catalog.first().id
            target.lastModel.isNotBlank() -> target.lastModel
            catalog.isNotEmpty() -> catalog.first().id
            else -> ""
        }
        settingsStore.update { it.copy(activeProviderId = id, model = model.ifBlank { it.model }) }
        if (catalog.isEmpty()) refreshAllModels() else refreshContextUsage()
    }

    fun addProvider(name: String, baseUrl: String, apiKey: String) {
        val provider = Provider(
            id = newId(),
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
        )
        settingsStore.update { it.copy(providers = it.providers + provider, activeProviderId = provider.id) }
        notify("已添加 ${provider.displayName}")
        refreshAllModels()
    }

    fun updateProvider(provider: Provider) {
        settingsStore.update { settings ->
            settings.copy(
                providers = settings.providers.map { if (it.id == provider.id) provider else it },
            )
        }
        refreshAllModels()
    }

    fun deleteProvider(id: String) {
        val settings = settingsStore.current
        if (settings.providers.size <= 1) {
            notify("至少要保留一个服务")
            return
        }
        val remaining = settings.providers.filterNot { it.id == id }
        val nextActive = if (settings.activeProviderId == id) remaining.first() else null
        settingsStore.update {
            it.copy(
                providers = remaining,
                activeProviderId = nextActive?.id ?: it.activeProviderId,
                model = nextActive?.lastModel?.ifBlank { modelsFor(nextActive.id).firstOrNull()?.id.orEmpty() }
                    ?: it.model,
            )
        }
        _state.update { it.copy(modelsByProvider = it.modelsByProvider - id) }
        refreshContextUsage()
    }

    fun testProvider(provider: Provider) {
        if (_state.value.testingProviderId != null) return
        if (!provider.isUsable) {
            notify("请先填写 Base URL 和 API Key")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(testingProviderId = provider.id) }
            val result = runCatching { client.listModels(provider.normalizedBaseUrl, provider.apiKey) }
            _state.update { current ->
                val catalogs = current.modelsByProvider.toMutableMap()
                result.getOrNull()?.let { catalogs[provider.id] = it }
                current.copy(testingProviderId = null, modelsByProvider = catalogs)
            }
            result.fold(
                onSuccess = { notify("连接成功，可用模型 ${it.size} 个") },
                onFailure = { notify(it.message ?: "连接失败") },
            )
            refreshContextUsage()
        }
    }

    fun selectModel(providerId: String, modelId: String) {
        val settings = settingsStore.current
        settingsStore.update { current ->
            current.copy(
                activeProviderId = providerId,
                model = modelId,
                providers = current.providers.map {
                    if (it.id == providerId) it.copy(lastModel = modelId) else it
                },
            )
        }
        if (settings.activeProviderId != providerId && _state.value.streaming) stop()

        val conversation = _state.value.conversation
        if (conversation != null && conversation.messages.isEmpty()) {
            val next = conversation.copy(model = modelId, providerId = providerId)
            _state.update { it.copy(conversation = next) }
            store.put(next, touch = false)
        }
        refreshContextUsage()
    }

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        settingsStore.update(block)
        refreshContextUsage()
    }

    // ------------------------------------------------------------ conversation

    fun openMostRecentOrNew() {
        if (_state.value.conversation != null) return
        val newest = store.items.value.firstOrNull()
        if (newest != null) {
            _state.update { it.copy(conversation = newest) }
            restoreProviderAndModel(newest)
            refreshContextUsage()
        } else {
            newConversation()
        }
    }

    fun newConversation() {
        if (_state.value.streaming) stop()
        val existing = _state.value.conversation
        if (existing != null && existing.messages.isEmpty()) return
        val settings = settingsStore.current
        val created = Conversation(model = settings.model, providerId = settings.activeProviderId)
        _state.update { it.copy(conversation = created, pendingAttachments = emptyList()) }
        store.put(created)
        refreshContextUsage()
    }

    fun selectConversation(id: String) {
        if (_state.value.conversation?.id == id) return
        if (_state.value.streaming) stop()
        val conversation = store.get(id) ?: return
        _state.update { it.copy(conversation = conversation, pendingAttachments = emptyList()) }
        restoreProviderAndModel(conversation)
        refreshContextUsage()
    }

    /** Reopening a conversation should put you back on the service and model it was using. */
    private fun restoreProviderAndModel(conversation: Conversation) {
        val settings = settingsStore.current
        val providerExists = conversation.providerId.isNotBlank() &&
            settings.providers.any { it.id == conversation.providerId }
        val provider = if (providerExists) conversation.providerId else settings.activeProviderId
        val model = conversation.model.ifBlank { settings.model }
        if (provider == settings.activeProviderId && model == settings.model) return
        settingsStore.update { it.copy(activeProviderId = provider, model = model) }
    }

    fun deleteConversation(id: String) {
        val wasActive = _state.value.conversation?.id == id
        if (wasActive && _state.value.streaming) stop()
        store.delete(id)
        if (wasActive) {
            val next = store.items.value.firstOrNull()
            _state.update { it.copy(conversation = next, pendingAttachments = emptyList()) }
            if (next == null) newConversation() else restoreProviderAndModel(next)
            refreshContextUsage()
        }
        viewModelScope.launch { store.pruneAttachments() }
    }

    fun renameConversation(id: String, title: String) {
        val target = store.get(id) ?: return
        val renamed = target.copy(title = title.trim())
        store.put(renamed, touch = false)
        if (_state.value.conversation?.id == id) {
            _state.update { it.copy(conversation = renamed) }
        }
    }

    fun deleteAllConversations() {
        if (_state.value.streaming) stop()
        store.deleteAll()
        _state.update { it.copy(conversation = null, pendingAttachments = emptyList()) }
        newConversation()
    }

    /** Renders a conversation as markdown for the share sheet. */
    fun exportMarkdown(conversation: Conversation): String = buildString {
        appendLine("# ${conversation.displayTitle}")
        appendLine()
        appendLine("- 模型：${conversation.model.ifBlank { "未记录" }}")
        appendLine("- 时间：${formatTimestamp(conversation.createdAt)}")
        appendLine("- 消息：${conversation.messages.size} 条")
        appendLine()
        conversation.messages.forEach { message ->
            when (message.role) {
                Role.USER -> {
                    appendLine("## 我")
                    appendLine()
                    if (message.attachments.isNotEmpty()) {
                        appendLine("_（附 ${message.attachments.size} 张图片）_")
                        appendLine()
                    }
                    appendLine(message.content)
                }
                else -> {
                    appendLine("## ${message.model.ifBlank { "助手" }}")
                    appendLine()
                    if (message.reasoning.isNotBlank()) {
                        appendLine("<details><summary>思考过程</summary>")
                        appendLine()
                        appendLine(message.reasoning)
                        appendLine()
                        appendLine("</details>")
                        appendLine()
                    }
                    appendLine(message.content)
                    message.error?.let {
                        appendLine()
                        appendLine("> 错误：$it")
                    }
                }
            }
            appendLine()
        }
    }

    // -------------------------------------------------------------- attachments

    /**
     * Picking an image on a text-only model used to be impossible: the button was simply disabled,
     * which reads as "this app can't do images". Instead, move the conversation to a model that
     * can see — the user's intent is clear — and only refuse when the service offers no such model.
     */
    private fun ensureImageCapableModel(): Boolean {
        val settings = settingsStore.current
        if (!imagesDisallowed(settings.model)) return true

        val candidate = modelsFor(settings.activeProviderId).firstOrNull { it.acceptsImages }
        if (candidate == null) {
            notify("${settings.activeProvider?.displayName ?: "当前服务"}没有支持图片的模型")
            return false
        }
        selectModel(settings.activeProviderId, candidate.id)
        notify("已切换到 ${candidate.id} 以便读取图片")
        return true
    }

    fun attachImage(uri: Uri) {
        if (!ensureImageCapableModel()) return
        viewModelScope.launch {
            _state.update { it.copy(attaching = true) }
            val decoded = decodeForUpload(appContext, uri)
            if (decoded == null) {
                _state.update { it.copy(attaching = false) }
                notify("无法读取这张图片")
                return@launch
            }
            val attachment = store.writeImage(
                bytes = decoded.bytes,
                mimeType = decoded.mimeType,
                displayName = decoded.displayName,
                width = decoded.width,
                height = decoded.height,
            )
            _state.update {
                it.copy(pendingAttachments = it.pendingAttachments + attachment, attaching = false)
            }
            refreshContextUsage()
        }
    }

    /**
     * Files take one of two routes. Anything that decodes as text is inlined into the prompt, which
     * works on every model. A PDF can only be rasterised by the platform, so it becomes a short
     * stack of page images and therefore needs a model that can see.
     */
    fun attachFile(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(attaching = true) }

            if (isPdf(appContext, uri)) {
                if (!ensureImageCapableModel()) {
                    _state.update { it.copy(attaching = false) }
                    return@launch
                }
                val pages = renderPdfPages(appContext, uri)
                if (pages.isEmpty()) {
                    _state.update { it.copy(attaching = false) }
                    notify("这个 PDF 读不出内容")
                    return@launch
                }
                val attachments = pages.map { page ->
                    store.writeImage(
                        bytes = page.bytes,
                        mimeType = page.mimeType,
                        displayName = page.displayName,
                        width = page.width,
                        height = page.height,
                    )
                }
                _state.update {
                    it.copy(pendingAttachments = it.pendingAttachments + attachments, attaching = false)
                }
                notify("已加入 PDF 前 ${pages.size} 页")
                refreshContextUsage()
                return@launch
            }

            val document = readTextDocument(appContext, uri)
            if (document == null) {
                _state.update { it.copy(attaching = false) }
                notify("这个格式读不出文本，可以试试图片或纯文本文件")
                return@launch
            }
            val attachment = store.writeDocument(
                text = document.text,
                displayName = document.displayName,
                mimeType = document.mimeType,
                byteCount = document.byteCount,
                truncated = document.truncated,
            )
            _state.update {
                it.copy(pendingAttachments = it.pendingAttachments + attachment, attaching = false)
            }
            if (document.truncated) notify("${document.displayName} 太长，已截取开头部分")
            refreshContextUsage()
        }
    }

    fun removeAttachment(id: String) {
        _state.update { current ->
            current.copy(pendingAttachments = current.pendingAttachments.filterNot { it.id == id })
        }
        refreshContextUsage()
    }

    fun attachmentFile(attachment: Attachment) = store.attachmentFile(attachment)

    // ---------------------------------------------------------------- messaging

    fun send(text: String) {
        val settings = settingsStore.current
        val trimmed = text.trim()
        val attachments = _state.value.pendingAttachments
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        if (_state.value.streaming) return

        if (!settings.isConfigured) {
            notify("请先在设置中配置服务和模型")
            return
        }
        if (attachments.isNotEmpty() && imagesDisallowed(settings.model)) {
            notify("${settings.model} 不支持图片输入，请切换到多模态模型")
            return
        }

        val conversation = _state.value.conversation
            ?: Conversation(model = settings.model, providerId = settings.activeProviderId)
                .also { created -> _state.update { it.copy(conversation = created) } }

        val user = ChatMessage(
            role = Role.USER,
            content = trimmed,
            attachments = attachments,
            model = settings.model,
        )
        val assistant = ChatMessage(role = Role.ASSISTANT, model = settings.model)
        val next = conversation.copy(
            messages = conversation.messages + user + assistant,
            model = settings.model,
            providerId = settings.activeProviderId,
        )
        _state.update { it.copy(conversation = next, pendingAttachments = emptyList()) }
        store.put(next)
        rememberModelForActiveProvider(settings.model)
        runTurn(next.id, assistant.id)
    }

    /**
     * Records the model a service was last actually used with, so switching away and back restores
     * the pair instead of carrying the other service's model over.
     */
    private fun rememberModelForActiveProvider(model: String) {
        val settings = settingsStore.current
        if (model.isBlank()) return
        if (settings.activeProvider?.lastModel == model) return
        settingsStore.update { current ->
            current.copy(
                providers = current.providers.map {
                    if (it.id == current.activeProviderId) it.copy(lastModel = model) else it
                },
            )
        }
    }

    fun regenerate(assistantMessageId: String) {
        if (_state.value.streaming) return
        val conversation = _state.value.conversation ?: return
        val index = conversation.messages.indexOfFirst { it.id == assistantMessageId }
        if (index < 0) return

        val settings = settingsStore.current
        val kept = conversation.messages.take(index)
        if (kept.none { it.role == Role.USER }) return
        val assistant = ChatMessage(role = Role.ASSISTANT, model = settings.model)
        val next = conversation.copy(
            messages = kept + assistant,
            model = settings.model,
            providerId = settings.activeProviderId,
        )
        _state.update { it.copy(conversation = next) }
        store.put(next)
        runTurn(next.id, assistant.id)
    }

    fun editAndResend(userMessageId: String, newText: String) {
        if (_state.value.streaming) return
        val conversation = _state.value.conversation ?: return
        val index = conversation.messages.indexOfFirst { it.id == userMessageId }
        if (index < 0) return
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return

        val settings = settingsStore.current
        val edited = conversation.messages[index].copy(
            content = trimmed,
            createdAt = System.currentTimeMillis(),
        )
        val assistant = ChatMessage(role = Role.ASSISTANT, model = settings.model)
        val next = conversation.copy(
            messages = conversation.messages.take(index) + edited + assistant,
            model = settings.model,
            providerId = settings.activeProviderId,
        )
        _state.update { it.copy(conversation = next) }
        store.put(next)
        runTurn(next.id, assistant.id)
    }

    fun deleteMessage(messageId: String) {
        val conversation = _state.value.conversation ?: return
        if (_state.value.streamingMessageId == messageId) stop()
        val next = conversation.copy(messages = conversation.messages.filterNot { it.id == messageId })
        _state.update { it.copy(conversation = next) }
        store.put(next)
        refreshContextUsage()
        viewModelScope.launch { store.pruneAttachments() }
    }

    /** A conversation's own prompt wins over the global one; blank falls through. */
    private fun effectiveSystemPrompt(conversation: Conversation?, settings: AppSettings): String =
        conversation?.systemPrompt?.takeIf { it.isNotBlank() } ?: settings.systemPrompt

    fun conversationSystemPrompt(): String = _state.value.conversation?.systemPrompt.orEmpty()

    fun setConversationSystemPrompt(prompt: String) {
        val conversation = _state.value.conversation ?: return
        val next = conversation.copy(systemPrompt = prompt.trim())
        _state.update { it.copy(conversation = next) }
        store.put(next, touch = false)
        refreshContextUsage()
    }

    fun togglePinned(id: String) {
        val target = store.get(id) ?: return
        val next = target.copy(pinned = !target.pinned)
        store.put(next, touch = false)
        if (_state.value.conversation?.id == id) {
            _state.update { it.copy(conversation = next) }
        }
    }

    /**
     * Asks for the rest of an answer that stopped at the output cap. Sent as an ordinary user turn
     * rather than as a prefix completion, because prefix continuation is not portable across
     * OpenAI-compatible gateways.
     */
    fun continueLast() {
        if (_state.value.streaming) return
        val conversation = _state.value.conversation ?: return
        val last = conversation.messages.lastOrNull() ?: return
        if (!last.truncatedByLength) return
        send("请从上次中断的地方继续，不要重复已经写过的内容。")
    }

    fun stop() {
        if (streamJob?.isActive != true) return
        stopRequested = true
        streamJob?.cancel()
    }

    fun notify(message: String) {
        _messages.trySend(message)
    }

    /** Text shared into the app from another app lands in the composer, not straight into a turn. */
    fun ingestSharedText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _drafts.trySend(trimmed)
    }

    fun ingestSharedImages(uris: List<Uri>) {
        uris.take(6).forEach { attachImage(it) }
    }

    // ------------------------------------------------------------ context meter

    /**
     * Recomputed at the few moments it can change — not per streamed token, which would rescan the
     * whole transcript sixteen times a second for a number nobody is reading mid-answer.
     */
    private fun refreshContextUsage() {
        val settings = settingsStore.current
        val info = modelInfo(settings.model)
        val window = info?.contextLength ?: 0
        val budget = settings.contextBudget(window, info?.maxOutputLength ?: 0)

        val conversation = _state.value.conversation
        val prompt = effectiveSystemPrompt(conversation, settings)
        val systemCost = if (prompt.isBlank()) 0 else TokenEstimate.ofMessage(prompt)
        // Attachments waiting in the composer will be sent with the next turn, so they occupy the
        // budget now — otherwise the meter can read over budget while still claiming nothing
        // will be trimmed.
        val pendingCost = _state.value.pendingAttachments.tokens
        val history = conversation?.messages
            ?.filter { it.error == null && !it.isBlank }
            .orEmpty()

        val plan = planContextWindow(history, budget, systemCost + pendingCost)
        _state.update {
            it.copy(
                contextUsage = ContextUsage(
                    usedTokens = plan.usedTokens,
                    budgetTokens = budget,
                    windowTokens = window,
                    droppedMessages = plan.droppedCount,
                ),
            )
        }
    }

    // ---------------------------------------------------------------- internals

    private fun runTurn(conversationId: String, assistantId: String) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val settings = settingsStore.current
            val startedAt = System.currentTimeMillis()
            stopRequested = false
            _state.update { it.copy(streaming = true, streamingMessageId = assistantId) }

            val content = StringBuilder()
            val reasoning = StringBuilder()
            var usage = Usage()
            var finishReason = ""
            var failure: String? = null
            var cancelled = false

            try {
                val outgoing = buildOutgoing(conversationId, assistantId, settings)
                val temperature = settings.temperature.takeIf {
                    settings.useTemperature && modelInfo(settings.model)?.supportsTemperature != false
                }

                if (settings.stream) {
                    var lastPublish = 0L
                    var lastPersist = startedAt
                    client.streamChat(
                        baseUrl = settings.baseUrl,
                        apiKey = settings.apiKey,
                        model = settings.model,
                        messages = outgoing,
                        temperature = temperature,
                        maxTokens = settings.maxTokens,
                    ).collect { event ->
                        when (event) {
                            is StreamEvent.Content -> content.append(event.text)
                            is StreamEvent.Reasoning -> reasoning.append(event.text)
                            is StreamEvent.Tokens -> usage = event.usage
                            is StreamEvent.Finished -> finishReason = event.reason
                        }
                        // Repainting per token is wasted work; ~16 fps is indistinguishable and cheap.
                        val now = System.currentTimeMillis()
                        if (now - lastPublish >= 60) {
                            lastPublish = now
                            applyDelta(conversationId, assistantId, content, reasoning, now - startedAt, usage)
                            if (now - lastPersist >= 1500) {
                                lastPersist = now
                                persist(conversationId)
                            }
                        }
                    }
                } else {
                    val result = client.complete(
                        baseUrl = settings.baseUrl,
                        apiKey = settings.apiKey,
                        model = settings.model,
                        messages = outgoing,
                        temperature = temperature,
                        maxTokens = settings.maxTokens,
                    )
                    content.append(result.content)
                    reasoning.append(result.reasoning)
                    usage = result.usage
                    finishReason = result.finishReason
                }
            } catch (stop: CancellationException) {
                cancelled = true
            } catch (error: Throwable) {
                if (stopRequested) {
                    cancelled = true
                } else {
                    failure = when (error) {
                        is ApiException -> error.message
                        else -> error.message?.takeIf { it.isNotBlank() }
                    } ?: "请求失败，请检查网络"
                }
            }

            // The job may already be cancelled here, so finishing must be uncancellable.
            withContext(NonCancellable) {
                finishTurn(
                    conversationId = conversationId,
                    assistantId = assistantId,
                    content = content.toString(),
                    reasoning = reasoning.toString(),
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    usage = usage,
                    finishReason = if (cancelled) "stopped" else finishReason,
                    failure = failure,
                )
            }
        }
    }

    /** Assembles the request; [planContextWindow] decides how much history fits the budget. */
    private suspend fun buildOutgoing(
        conversationId: String,
        assistantId: String,
        settings: AppSettings,
    ): List<OutgoingMessage> {
        val conversation = _state.value.conversation?.takeIf { it.id == conversationId }
            ?: store.get(conversationId)
            ?: throw ApiException("对话已不存在")

        val history = conversation.messages
            .filterNot { it.id == assistantId }
            .filter { it.error == null }
            .filterNot { it.isBlank }

        val info = modelInfo(settings.model)
        val budget = settings.contextBudget(info?.contextLength ?: 0, info?.maxOutputLength ?: 0)
        val prompt = effectiveSystemPrompt(conversation, settings)
        val systemCost = if (prompt.isBlank()) 0 else TokenEstimate.ofMessage(prompt)
        val plan = planContextWindow(history, budget, systemCost)
        val startIndex = plan.startIndex

        val windowed = history.drop(startIndex)
        val allowImages = !imagesDisallowed(settings.model)
        val outgoing = mutableListOf<OutgoingMessage>()
        if (prompt.isNotBlank()) {
            outgoing += OutgoingMessage("system", prompt.trim())
        }
        windowed.forEach { message ->
            // Reasoning traces are never echoed back — the API rejects them on input.
            val images = if (allowImages) {
                message.attachments.filter { it.isImage }.mapNotNull { attachment ->
                    store.readAttachment(attachment)?.let { toDataUrl(it, attachment.mimeType) }
                }
            } else {
                emptyList()
            }
            // Documents are inlined as delimited blocks so the model can tell file from question.
            val documents = message.attachments.filter { it.isDocument }.mapNotNull { attachment ->
                store.readDocumentText(attachment)?.let { text ->
                    buildString {
                        append("<file name=\"")
                        append(attachment.label)
                        append("\"")
                        if (attachment.truncated) append(" truncated=\"true\"")
                        appendLine(">")
                        appendLine(text)
                        append("</file>")
                    }
                }
            }
            val body = buildString {
                documents.forEach {
                    appendLine(it)
                    appendLine()
                }
                append(message.content)
            }.trim()
            val text = when {
                body.isNotBlank() -> body
                images.isNotEmpty() -> "请看这张图片。"
                else -> return@forEach
            }
            outgoing += OutgoingMessage(message.role.wire, text, images)
        }
        return outgoing
    }

    private fun applyDelta(
        conversationId: String,
        assistantId: String,
        content: StringBuilder,
        reasoning: StringBuilder,
        elapsedMs: Long,
        usage: Usage,
    ) {
        mutateAssistant(conversationId, assistantId) { message ->
            message.copy(
                content = content.toString(),
                reasoning = reasoning.toString(),
                elapsedMs = elapsedMs,
                usage = usage,
            )
        }
    }

    private suspend fun finishTurn(
        conversationId: String,
        assistantId: String,
        content: String,
        reasoning: String,
        elapsedMs: Long,
        usage: Usage,
        finishReason: String,
        failure: String?,
    ) {
        mutateAssistant(conversationId, assistantId) { message ->
            message.copy(
                content = content,
                reasoning = reasoning,
                elapsedMs = elapsedMs,
                usage = usage,
                finishReason = finishReason,
                error = failure,
            )
        }

        val conversation = _state.value.conversation?.takeIf { it.id == conversationId }
        if (conversation != null && conversation.title.isBlank()) {
            val first = conversation.messages.firstOrNull { it.role == Role.USER }?.content?.trim()
            if (!first.isNullOrEmpty()) {
                val title = first.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(30).orEmpty()
                if (title.isNotEmpty()) {
                    val titled = conversation.copy(title = title)
                    _state.update { if (it.conversation?.id == conversationId) it.copy(conversation = titled) else it }
                    store.put(titled, touch = false)
                }
            }
        }

        _state.update { it.copy(streaming = false, streamingMessageId = null) }
        persist(conversationId)
        store.flush(conversationId)
        refreshContextUsage()
        failure?.let { notify(it) }
    }

    private fun mutateAssistant(
        conversationId: String,
        assistantId: String,
        block: (ChatMessage) -> ChatMessage,
    ) {
        _state.update { current ->
            val conversation = current.conversation ?: return@update current
            if (conversation.id != conversationId) return@update current
            val messages = conversation.messages.map { if (it.id == assistantId) block(it) else it }
            current.copy(conversation = conversation.copy(messages = messages))
        }
    }

    private fun persist(conversationId: String) {
        val conversation = _state.value.conversation ?: return
        if (conversation.id != conversationId) return
        store.put(conversation)
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(container: AppContainer, appContext: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
                    settingsStore = container.settings,
                    store = container.conversations,
                    client = container.client,
                    updater = container.updater,
                    appContext = appContext,
                ) as T
            }
    }
}
