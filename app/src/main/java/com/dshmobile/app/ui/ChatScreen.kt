package com.dshmobile.app.ui

import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshmobile.app.data.ChatMessage
import com.dshmobile.app.data.Conversation
import com.dshmobile.app.ui.components.SmallIconButton
import com.dshmobile.app.ui.theme.MonoFamily
import com.dshmobile.app.util.copyToClipboard
import com.dshmobile.app.util.formatTokenBudget
import kotlinx.coroutines.launch
import java.io.File

private val SUGGESTIONS = listOf(
    "用一段话解释一下 Transformer 的注意力机制",
    "帮我写一个 Kotlin 的防抖函数，带单元测试",
    "把这段需求整理成任务清单：",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val storeLoaded by viewModel.storeLoaded.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf(TextFieldValue()) }
    var showModelSheet by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var editTarget by remember { mutableStateOf<ChatMessage?>(null) }

    var showAttachSheet by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var editPrompt by remember { mutableStateOf(false) }
    var captureUri by remember { mutableStateOf<Uri?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.attachImage(it) }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.attachFile(it) }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = captureUri
        if (saved && uri != null) viewModel.attachImage(uri)
    }
    val speak = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
        if (!spoken.isNullOrEmpty()) {
            val joined = if (input.text.isBlank()) spoken else input.text.trimEnd() + " " + spoken
            input = TextFieldValue(joined, androidx.compose.ui.text.TextRange(joined.length))
        }
    }

    // Camera output goes to a cache file exposed through the app's FileProvider.
    fun launchCamera() {
        val target = runCatching {
            val dir = File(context.cacheDir, "captures").apply { mkdirs() }
            val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
        if (target == null) {
            viewModel.notify("无法创建拍照文件")
            return
        }
        captureUri = target
        runCatching { takePicture.launch(target) }
            .onFailure { viewModel.notify("这台设备没有可用的相机") }
    }

    fun launchVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出要发送的内容")
        }
        runCatching { speak.launch(intent) }
            .onFailure { viewModel.notify("这台设备没有语音输入") }
    }

    LaunchedEffect(storeLoaded) {
        if (storeLoaded) viewModel.openMostRecentOrNew()
    }
    LaunchedEffect(Unit) {
        viewModel.notices.collect { snackbarHostState.showSnackbar(it) }
    }
    // Text shared in from another app, or dictated, arrives as a draft rather than a sent turn.
    LaunchedEffect(Unit) {
        viewModel.drafts.collect { draft ->
            val joined = if (input.text.isBlank()) draft else input.text.trimEnd() + "\n\n" + draft
            input = TextFieldValue(joined, androidx.compose.ui.text.TextRange(joined.length))
        }
    }

    val messages = state.conversation?.messages ?: emptyList()
    // Index of the trailing spacer; scrolling it into view lands exactly at the end of the content.
    val tailIndex = messages.size

    // Whether the very end of the transcript is on screen — drives the jump-to-bottom button.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) return@derivedStateOf true
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 &&
                // A few pixels of slack: content that ends flush with the fold still counts as
                // "at the bottom", otherwise the jump button sits there permanently.
                (last.offset + last.size) <= info.viewportEndOffset + 24
        }
    }

    /*
     * Auto-follow, measured from the layout rather than from reader input.
     *
     * The trap this avoids: any strict "is the very end on screen" test flips false on the first
     * chunk that outruns the scroll and then never recovers, freezing the transcript mid-answer.
     * The list always ends with a tiny spacer item, so exactly one item legitimately sits below
     * the fold whenever the newest message is taller than the viewport — the threshold has to be
     * "more than the spacer", not "anything at all".
     *
     * Half a viewport of slack is far more than a repaint's worth of growth, so streaming stays
     * pinned, while a deliberate scroll back of a few lines sticks instead of being yanked down.
     */
    val followTail by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) return@derivedStateOf true
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            val itemsBelowFold = info.totalItemsCount - 1 - last.index
            if (itemsBelowFold > 1) return@derivedStateOf false
            val overflow = (last.offset + last.size) - info.viewportEndOffset
            overflow <= info.viewportSize.height / 2
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(tailIndex)
    }
    val streamedLength = messages.lastOrNull()?.let { it.content.length + it.reasoning.length } ?: 0
    LaunchedEffect(streamedLength) {
        if (state.streaming && followTail && messages.isNotEmpty()) listState.scrollToItem(tailIndex)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                conversations = conversations,
                activeId = state.conversation?.id,
                onSelect = { id ->
                    viewModel.selectConversation(id)
                    scope.launch { drawerState.close() }
                },
                onNew = {
                    viewModel.newConversation()
                    input = TextFieldValue()
                    scope.launch { drawerState.close() }
                },
                onRename = { renameTarget = it },
                onDelete = { deleteTarget = it },
                onTogglePin = { viewModel.togglePinned(it.id) },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column(
                            modifier = Modifier.clickable { showModelSheet = true },
                        ) {
                            Text(
                                text = state.conversation?.displayTitle ?: "DSH Mobile",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = settings.model.ifBlank { "未选择模型" },
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Icon(
                                    imageVector = Icons.Outlined.ExpandMore,
                                    contentDescription = "切换模型",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp),
                                )
                                val usage = state.contextUsage
                                if (usage.usedTokens > 0 || usage.windowTokens > 0) {
                                    val trimmed = usage.droppedMessages > 0
                                    Text(
                                        text = buildString {
                                            append("  ")
                                            append(formatTokenBudget(usage.usedTokens).ifEmpty { "0" })
                                            if (usage.windowTokens > 0) {
                                                append(" / ")
                                                append(formatTokenBudget(usage.windowTokens))
                                            }
                                            if (trimmed) append(" · 裁剪 ${usage.droppedMessages}")
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = MonoFamily,
                                        ),
                                        color = if (trimmed) {
                                            MaterialTheme.colorScheme.tertiary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        SmallIconButton(
                            icon = Icons.Outlined.Menu,
                            contentDescription = "对话列表",
                            onClick = { scope.launch { drawerState.open() } },
                            size = 44.dp,
                            iconSize = 22.dp,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    actions = {
                        Box {
                            SmallIconButton(
                                icon = Icons.Outlined.MoreVert,
                                contentDescription = "更多",
                                onClick = { showOverflow = true },
                                size = 44.dp,
                                iconSize = 22.dp,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                            DropdownMenu(
                                expanded = showOverflow,
                                onDismissRequest = { showOverflow = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("本次对话的提示词") },
                                    leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        editPrompt = true
                                    },
                                )
                                state.conversation?.let { conversation ->
                                    DropdownMenuItem(
                                        text = { Text(if (conversation.pinned) "取消置顶" else "置顶对话") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.PushPin, contentDescription = null)
                                        },
                                        onClick = {
                                            showOverflow = false
                                            viewModel.togglePinned(conversation.id)
                                        },
                                    )
                                }
                                if (messages.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("导出为 Markdown") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.IosShare, contentDescription = null)
                                        },
                                        onClick = {
                                            showOverflow = false
                                            val conversation = state.conversation ?: return@DropdownMenuItem
                                            val markdown = viewModel.exportMarkdown(conversation)
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, conversation.displayTitle)
                                                putExtra(Intent.EXTRA_TITLE, conversation.displayTitle)
                                                putExtra(Intent.EXTRA_TEXT, markdown)
                                            }
                                            context.startActivity(
                                                Intent.createChooser(intent, "导出为 Markdown"),
                                            )
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除这个对话") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            showOverflow = false
                                            deleteTarget = state.conversation
                                        },
                                    )
                                }
                            }
                        }
                        SmallIconButton(
                            icon = Icons.Outlined.Add,
                            contentDescription = "新建对话",
                            onClick = {
                                viewModel.newConversation()
                                input = TextFieldValue()
                            },
                            size = 44.dp,
                            iconSize = 22.dp,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                    ),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Composer(
                        value = input,
                        onValueChange = { input = it },
                        onSend = {
                            viewModel.send(input.text)
                            input = TextFieldValue()
                        },
                        onStop = viewModel::stop,
                        streaming = state.streaming,
                        attachments = state.pendingAttachments,
                        attachmentFile = viewModel::attachmentFile,
                        onRemoveAttachment = viewModel::removeAttachment,
                        onAttach = { showAttachSheet = true },
                        onVoice = { launchVoice() },
                        attaching = state.attaching,
                        sendOnEnter = settings.sendOnEnter,
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (messages.isEmpty()) {
                    WelcomePane(
                        model = settings.model,
                        onSuggestion = { input = TextFieldValue(it, androidx.compose.ui.text.TextRange(it.length)) },
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(
                            count = messages.size,
                            key = { index -> messages[index].id },
                        ) { index ->
                            val message = messages[index]
                            MessageItem(
                                message = message,
                                streaming = state.streaming && state.streamingMessageId == message.id,
                                showReasoning = settings.showReasoning,
                                attachmentFile = viewModel::attachmentFile,
                                onCopy = { text ->
                                    copyToClipboard(context, text)
                                    viewModel.notify("已复制")
                                },
                                onRegenerate = { viewModel.regenerate(lastAssistantId(messages, index)) },
                                onEdit = { editTarget = message },
                                onDelete = { viewModel.deleteMessage(message.id) },
                                onContinue = viewModel::continueLast,
                            )
                        }
                        item(key = "tail") { Spacer(Modifier.height(6.dp)) }
                    }
                }

                AnimatedVisibility(
                    // While a reply streams and the view is still tracking it, the button would
                    // flicker on every chunk that briefly outruns the scroll.
                    visible = !atBottom && messages.isNotEmpty() && !(state.streaming && followTail),
                    enter = fadeIn() + scaleIn(initialScale = 0.85f),
                    exit = fadeOut() + scaleOut(targetScale = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { scope.launch { listState.animateScrollToItem(tailIndex) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ArrowDownward,
                            contentDescription = "回到底部",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }

    if (showAttachSheet) {
        AttachSheet(
            imagesSupported = !viewModel.imagesDisallowed(settings.model),
            onPickImage = {
                showAttachSheet = false
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onCapture = {
                showAttachSheet = false
                launchCamera()
            },
            onPickFile = {
                showAttachSheet = false
                runCatching { pickFile.launch(arrayOf("*/*")) }
                    .onFailure { viewModel.notify("没有可用的文件选择器") }
            },
            onDismiss = { showAttachSheet = false },
        )
    }

    if (editPrompt) {
        TextPromptDialog(
            title = "本次对话的提示词",
            initialValue = viewModel.conversationSystemPrompt(),
            confirmLabel = "保存",
            singleLine = false,
            allowEmpty = true,
            supporting = "留空则使用设置里的全局提示词。",
            onConfirm = {
                viewModel.setConversationSystemPrompt(it)
                editPrompt = false
            },
            onDismiss = { editPrompt = false },
        )
    }

    if (showModelSheet) {
        ModelPickerSheet(
            providers = settings.providers,
            modelsByProvider = state.modelsByProvider,
            loading = state.modelsLoading,
            error = state.modelsError,
            activeProviderId = settings.activeProviderId,
            currentModel = settings.model,
            onSelect = { providerId, modelId ->
                viewModel.selectModel(providerId, modelId)
                showModelSheet = false
            },
            onRefresh = viewModel::refreshModels,
            onDismiss = { showModelSheet = false },
        )
    }

    renameTarget?.let { target ->
        TextPromptDialog(
            title = "重命名对话",
            initialValue = target.title.ifBlank { target.displayTitle },
            confirmLabel = "保存",
            onConfirm = {
                viewModel.renameConversation(target.id, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除对话") },
            text = { Text("「${target.displayTitle}」及其 ${target.messages.size} 条消息会被永久删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConversation(target.id)
                        deleteTarget = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    editTarget?.let { target ->
        TextPromptDialog(
            title = "编辑并重新发送",
            initialValue = target.content,
            confirmLabel = "重新发送",
            singleLine = false,
            onConfirm = {
                viewModel.editAndResend(target.id, it)
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }
}

/**
 * Regeneration always replays from the assistant turn. Tapping regenerate on a user message should
 * therefore target the reply that follows it, not the user message itself.
 */
private fun lastAssistantId(messages: List<ChatMessage>, index: Int): String {
    val here = messages[index]
    if (here.role != com.dshmobile.app.data.Role.USER) return here.id
    return messages.drop(index + 1).firstOrNull()?.id ?: here.id
}

@Composable
private fun WelcomePane(
    model: String,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .background(scheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = scheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = "开始新的对话",
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = model.ifBlank { "尚未选择模型" },
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
            color = scheme.primary,
        )
        Spacer(Modifier.height(26.dp))
        SUGGESTIONS.forEach { suggestion ->
            Text(
                text = suggestion,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .background(scheme.surfaceContainer, RoundedCornerShape(12.dp))
                    .border(1.dp, scheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .clickable { onSuggestion(suggestion) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
fun TextPromptDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    singleLine: Boolean = true,
    allowEmpty: Boolean = false,
    supporting: String? = null,
) {
    var text by remember {
        mutableStateOf(
            TextFieldValue(initialValue, androidx.compose.ui.text.TextRange(initialValue.length)),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 8,
                supportingText = supporting?.let { hint -> { Text(hint) } },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.text) },
                enabled = allowEmpty || text.text.isNotBlank(),
            ) {
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
