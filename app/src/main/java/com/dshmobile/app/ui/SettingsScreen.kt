package com.dshmobile.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshmobile.app.data.Provider
import com.dshmobile.app.data.ThemeMode
import com.dshmobile.app.ui.components.SmallIconButton
import com.dshmobile.app.ui.theme.MonoFamily
import com.dshmobile.app.util.formatTokenBudget
import java.util.Locale

/** Budget ladder offered in manual mode; trimmed to what the selected model can actually hold. */
private val BUDGET_LADDER = listOf(
    4_096, 8_192, 16_384, 32_768, 65_536, 131_072, 262_144, 524_288, 1_048_576,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showModelSheet by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Provider?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteProvider by remember { mutableStateOf<Provider?>(null) }

    LaunchedEffect(Unit) {
        viewModel.notices.collect { snackbarHostState.showSnackbar(it) }
    }

    val modelInfo = viewModel.modelInfo(settings.model)
    val window = modelInfo?.contextLength ?: 0
    val budget = settings.contextBudget(window, modelInfo?.maxOutputLength ?: 0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    SmallIconButton(
                        icon = Icons.Outlined.ArrowBack,
                        contentDescription = "返回",
                        onClick = onBack,
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Section("服务", trailing = {
                SmallIconButton(
                    icon = Icons.Outlined.Add,
                    contentDescription = "添加服务",
                    onClick = { adding = true },
                    tint = MaterialTheme.colorScheme.primary,
                )
            }) {
                settings.providers.forEachIndexed { index, provider ->
                    if (index > 0) Spacer(Modifier.height(6.dp))
                    ProviderRow(
                        provider = provider,
                        active = provider.id == settings.activeProviderId,
                        modelCount = viewModel.modelsFor(provider.id).size,
                        onSelect = { viewModel.selectProvider(provider.id) },
                        onEdit = { editing = provider },
                        onDelete = { deleteProvider = provider },
                    )
                }
            }

            Section("模型") {
                NavRow(
                    title = "当前模型",
                    value = settings.model.ifBlank { "未选择" },
                    mono = true,
                    onClick = { showModelSheet = true },
                )
                if (modelInfo != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = buildList {
                            if (window > 0) add("上下文 ${formatTokenBudget(window)}")
                            modelInfo.maxOutputLength.takeIf { it > 0 }?.let {
                                add("单次最多 ${formatTokenBudget(it)}")
                            }
                            if (modelInfo.acceptsImages) add("支持图片")
                            if (modelInfo.hasReasoning) add("支持思考")
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Section("上下文") {
                ToggleRow(
                    title = "自动管理上下文",
                    subtitle = when {
                        !settings.autoContextBudget -> "已关闭，按下面设定的预算裁剪"
                        window > 0 -> "按模型窗口自动留出回复空间，当前预算 ${formatTokenBudget(budget)}"
                        else -> "按模型窗口自动留出回复空间"
                    },
                    checked = settings.autoContextBudget,
                    onCheckedChange = { value ->
                        viewModel.updateSettings { it.copy(autoContextBudget = value) }
                    },
                )
                if (!settings.autoContextBudget) {
                    val ladder = remember(window) {
                        val allowed = if (window > 0) {
                            BUDGET_LADDER.filter { it <= window }.ifEmpty { listOf(window) }
                        } else {
                            BUDGET_LADDER
                        }
                        allowed
                    }
                    val index = ladder.indexOfFirst { it >= settings.contextBudgetTokens }
                        .let { if (it < 0) ladder.lastIndex else it }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "上下文预算",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (window > 0) {
                                "${formatTokenBudget(ladder[index])} / ${formatTokenBudget(window)}"
                            } else {
                                formatTokenBudget(ladder[index])
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = index.toFloat(),
                        onValueChange = { raw ->
                            val picked = ladder[raw.toInt().coerceIn(0, ladder.lastIndex)]
                            viewModel.updateSettings { it.copy(contextBudgetTokens = picked) }
                        },
                        valueRange = 0f..ladder.lastIndex.toFloat().coerceAtLeast(0f),
                        steps = (ladder.size - 2).coerceAtLeast(0),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                Text(
                    text = "超出预算时会丢掉最早的消息，估算按中文约 1 字 1 token、英文约 4 字符 1 token。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("生成") {
                ToggleRow(
                    title = "流式输出",
                    subtitle = "逐字显示回复，可随时停止",
                    checked = settings.stream,
                    onCheckedChange = { value -> viewModel.updateSettings { it.copy(stream = value) } },
                )
                ToggleRow(
                    title = "自动展开思考过程",
                    subtitle = "思考中默认展开，出答案后自动收起",
                    checked = settings.showReasoning,
                    onCheckedChange = { value -> viewModel.updateSettings { it.copy(showReasoning = value) } },
                )
                ToggleRow(
                    title = "自定义 temperature",
                    subtitle = if (settings.useTemperature) {
                        "当前 ${String.format(Locale.US, "%.2f", settings.temperature)}"
                    } else {
                        "关闭时使用服务端默认值"
                    },
                    checked = settings.useTemperature,
                    onCheckedChange = { value -> viewModel.updateSettings { it.copy(useTemperature = value) } },
                )
                if (settings.useTemperature) {
                    Slider(
                        value = settings.temperature,
                        onValueChange = { value ->
                            viewModel.updateSettings { it.copy(temperature = value) }
                        },
                        valueRange = 0f..2f,
                        steps = 19,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = if (settings.maxTokens == 0) "" else settings.maxTokens.toString(),
                    onValueChange = { value ->
                        val parsed = value.filter { it.isDigit() }.take(7).toIntOrNull() ?: 0
                        viewModel.updateSettings { it.copy(maxTokens = parsed) }
                    },
                    label = { Text("max_tokens") },
                    placeholder = { Text("留空表示不限制") },
                    supportingText = { Text("思考型模型会先消耗这个额度，设太小会截断答案") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.systemPrompt,
                    onValueChange = { value -> viewModel.updateSettings { it.copy(systemPrompt = value) } },
                    label = { Text("系统提示词") },
                    placeholder = { Text("可留空") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Section("界面") {
                Text(
                    text = "主题",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                val modes = listOf(
                    ThemeMode.SYSTEM to "跟随系统",
                    ThemeMode.LIGHT to "浅色",
                    ThemeMode.DARK to "深色",
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.updateSettings { it.copy(themeMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        ) {
                            Text(label)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    title = "回车发送",
                    subtitle = "关闭时回车换行，用发送按钮提交",
                    checked = settings.sendOnEnter,
                    onCheckedChange = { value -> viewModel.updateSettings { it.copy(sendOnEnter = value) } },
                )
            }

            Section("数据") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { confirmWipe = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "清空所有对话",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = "含所有消息与图片，不可恢复",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Section("更新") {
                UpdateSectionBody(
                    updater = viewModel.updater,
                    settings = settings,
                    onSettingsChange = viewModel::updateSettings,
                )
            }

            Section("关于") {
                Text(
                    text = "DSH Mobile",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "DeepSeek Harness 的安卓端对话客户端。可配置多个 OpenAI 兼容服务，" +
                        "会话与图片只存在本机应用私有目录里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
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

    if (adding) {
        ProviderEditorDialog(
            initial = null,
            testing = state.testingProviderId != null,
            onTest = viewModel::testProvider,
            onConfirm = { provider ->
                viewModel.addProvider(provider.name, provider.baseUrl, provider.apiKey)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }

    editing?.let { target ->
        ProviderEditorDialog(
            initial = target,
            testing = state.testingProviderId != null,
            onTest = viewModel::testProvider,
            onConfirm = { provider ->
                viewModel.updateProvider(provider)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    deleteProvider?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteProvider = null },
            title = { Text("删除服务") },
            text = { Text("「${target.displayName}」的地址和 Key 会被移除，已有对话不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProvider(target.id)
                        deleteProvider = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteProvider = null }) { Text("取消") } },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("清空所有对话") },
            text = { Text("所有对话、消息和已附加的图片都会被删除，无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllConversations()
                        confirmWipe = false
                        viewModel.notify("已清空")
                    },
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ProviderRow(
    provider: Provider,
    active: Boolean,
    modelCount: Int,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (active) scheme.primaryContainer.copy(alpha = 0.5f) else scheme.surfaceContainer,
                RoundedCornerShape(12.dp),
            )
            .border(
                width = 1.dp,
                color = if (active) scheme.primary.copy(alpha = 0.45f) else scheme.outlineVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp),
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect() }
            .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (active) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (active) "当前服务" else "切换到这个服务",
            tint = if (active) scheme.primary else scheme.outline,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (active) scheme.onPrimaryContainer else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildList {
                    add(provider.normalizedBaseUrl.removePrefix("https://").removePrefix("http://"))
                    if (modelCount > 0) add("$modelCount 个模型")
                    if (!provider.isUsable) add("缺少 Key")
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (active) {
                    scheme.onPrimaryContainer.copy(alpha = 0.75f)
                } else {
                    scheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SmallIconButton(
            icon = Icons.Outlined.DriveFileRenameOutline,
            contentDescription = "编辑服务",
            onClick = onEdit,
            size = 34.dp,
            iconSize = 17.dp,
            tint = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        )
        SmallIconButton(
            icon = Icons.Outlined.Delete,
            contentDescription = "删除服务",
            onClick = onDelete,
            size = 34.dp,
            iconSize = 17.dp,
            tint = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp, start = 2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    RoundedCornerShape(16.dp),
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NavRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    mono: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = if (mono) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 190.dp),
        )
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
